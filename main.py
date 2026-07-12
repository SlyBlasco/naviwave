import os
import sys
from pathlib import Path

# 1. CONFIGURACIÓN DE DRIVERS
dll_path = str(Path(__file__).parent.absolute())
if sys.platform == 'win32':
    try:
        os.add_dll_directory(dll_path)
        os.environ['PATH'] = dll_path + os.pathsep + os.environ['PATH']
    except Exception as e:
        print(f"Error configurando DLLs: {e}")

import numpy as np
from scipy import signal
from rtlsdr import RtlSdr, RtlSdrTcpClient
import sounddevice as sd
import soundfile as sf
import threading
import queue
import time
import socket
import struct
from datetime import datetime
from pyais import decode
from pyais.util import SixBitNibleEncoder
from db_manager import GestorBaseDatos

# --- PARÁMETROS ---
FRECUENCIA_CENTRAL  = 156_800_000
FRECUENCIA_AIS      = 162_025_000
SAMPLE_RATE         = 256_000
GANANCIA            = 12
AUDIO_RATE          = 48_000

# PARAMETROS TCP
RTL_TCP_HOST        = '192.168.1.72'
RTL_TCP_PORT        = 1234

# Tasa interna para AIS: 8 muestras exactas por bit a 9600 baud
# resample_poly(x, 3, 10): 256000 * 3/10 = 76800
AIS_FS  = 76_800
AIS_SPS = 8          # muestras por símbolo = 76800/9600

class ClienteRtlTcpPuro:
    """Cliente ultra rápido de Sockets para el rtl_tcp"""
    def __init__(self, host, port=1234):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((host, port))
        
        # Leer el header de bienvenida de rtl_tcp (12 bytes 'RTL0')
        header = self.sock.recv(12)
        if not header.startswith(b'RTL0'):
            raise ValueError("No se detectó un servidor rtl_tcp válido")

    def _enviar_comando(self, cmd, arg):
        # Envía el comando en formato binario (Big-Endian)
        self.sock.sendall(struct.pack('>BI', cmd, int(arg)))

    @property
    def center_freq(self): return 0
    @center_freq.setter
    def center_freq(self, freq):
        self._enviar_comando(0x01, int(freq))

    @property
    def sample_rate(self): return 0
    @sample_rate.setter
    def sample_rate(self, rate):
        self._enviar_comando(0x02, int(rate))

    @property
    def gain(self): return 0
    @gain.setter
    def gain(self, gain_val):
        if gain_val == 'auto':
            # 0x03 = Modo de Ganancia del Sintonizador (0 = Auto)
            self._enviar_comando(0x03, 0) 
            # 0x08 = AGC Interno del chip RTL2832U (1 = Encendido, maximiza la captura AIS)
            self._enviar_comando(0x08, 1) 
        else:
            # 0x03 = Modo de Ganancia del Sintonizador (1 = Manual)
            self._enviar_comando(0x03, 1) 
            # 0x08 = AGC Interno (0 = Apagado, vital para que la Voz no se sature)
            self._enviar_comando(0x08, 0) 
            # 0x04 = Establecer Ganancia (rtl_tcp espera el valor en décimas de dB, ej: 15.0 -> 150)
            self._enviar_comando(0x04, int(gain_val * 10))

    def read_samples(self, num_samples):
        # Leer bytes binarios puros (ultra rápido)
        bytes_esperados = num_samples * 2
        data = bytearray()
        
        while len(data) < bytes_esperados:
            chunk = self.sock.recv(bytes_esperados - len(data))
            if not chunk:
                raise ConnectionError("El servidor cerró la conexión")
            data.extend(chunk)
            
        # Transformación matemática a complejos
        iq = np.frombuffer(data, dtype=np.uint8).astype(np.float32)
        iq = (iq - 127.5) / 128.0
        return iq[0::2] + 1j * iq[1::2]

    def close(self):
        self.sock.close()

class GrabadorFondo:
    def __init__(self, sample_rate):
        self.sr = sample_rate
        self.cola = queue.Queue()
        self.archivo_actual = None
        self.grabaciones_dir = Path(__file__).parent / "grabaciones"
        self.grabaciones_dir.mkdir(parents=True, exist_ok=True)
        self.hilo = threading.Thread(target=self._trabajador, daemon=True)
        self.hilo.start()

    def iniciar(self, frecuencia):
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.nombre = f"NaviWave_{frecuencia/1e6:.3f}MHz_{timestamp}.wav"
        self.cola.put(("INICIAR", self.nombre))

    def grabar(self, audio):
        self.cola.put(("AUDIO", audio))

    def detener(self):
        self.cola.put(("DETENER", None))

    def _trabajador(self):
        # Instancia global de gestor de BD
        db_gestor = GestorBaseDatos()
        while True:
            comando, payload = self.cola.get()
            if comando == "INICIAR":
                if self.archivo_actual is None:
                    self.ruta = str(self.grabaciones_dir / payload)
                    self.archivo_actual = sf.SoundFile(
                        self.ruta, mode='w', samplerate=self.sr, channels=1
                    )
                    print(f"\n[REC 🔴] Grabando en: {payload}")
            elif comando == "AUDIO":
                if self.archivo_actual is not None:
                    self.archivo_actual.write(payload)
            elif comando == "DETENER":
                if self.archivo_actual is not None:
                    self.archivo_actual.close()
                    self.archivo_actual = None
                    db_gestor.guardarVoz(nombre=self.nombre, ruta=self.grabaciones_dir)
                    print("\n[REC ⏹️] Archivo guardado correctamente.")
            self.cola.task_done()


class SquelchAdaptativo:
    def __init__(self, margen_db, piso_inicial=-45.0):
        self.piso_ruido = piso_inicial
        self.margen = margen_db
        self.alpha_bajada = 0.1    
        self.alpha_subida = 0.01   
        self.alpha_bloqueo = 0.001 

    def evaluar(self, pwr_actual):
        umbral_disparo = self.piso_ruido + self.margen
        senal_activa = pwr_actual > umbral_disparo

        if not senal_activa:
            if pwr_actual < self.piso_ruido:
                self.piso_ruido = (1 - self.alpha_bajada) * self.piso_ruido + self.alpha_bajada * pwr_actual
            else:
                self.piso_ruido = (1 - self.alpha_subida) * self.piso_ruido + self.alpha_subida * pwr_actual
        else:
            self.piso_ruido = (1 - self.alpha_bloqueo) * self.piso_ruido + self.alpha_bloqueo * pwr_actual

        return senal_activa, self.piso_ruido, umbral_disparo


class ProcesadorNaviWave:
    def __init__(self, sr, audio_sr):
        self.sr      = sr
        self.audio_sr = audio_sr
        self.b_chan, self.a_chan = signal.butter(4, 12500 / (sr / 2), btype='low')
        self.zi_chan = signal.lfilter_zi(self.b_chan, self.a_chan)
        tau = 75e-6
        alpha = 1.0 / (1.0 + tau * audio_sr)
        self.b_deemph = [alpha]
        self.a_deemph = [1.0, -(1.0 - alpha)]
        self.zi_deemph = signal.lfilter_zi(self.b_deemph, self.a_deemph)

    def procesar(self, muestras):
        muestras -= np.mean(muestras)
        muestras, self.zi_chan = signal.lfilter(self.b_chan, self.a_chan, muestras, zi=self.zi_chan)
        audio = np.angle(muestras[1:] * np.conj(muestras[:-1]))
        audio_resampled = signal.resample_poly(audio, self.audio_sr, self.sr)
        audio_final, self.zi_deemph = signal.lfilter(
            self.b_deemph, self.a_deemph, audio_resampled, zi=self.zi_deemph
        )
        limite = np.max(np.abs(audio_final))
        if limite > 0:
            audio_final /= limite
        return audio_final.astype(np.float32) * 0.4


class ProcesadorAIS:

    # Factor de resample: 256000 * 3/10 = 76800
    _UP   = 3
    _DOWN = 10

    # Filtro matchado gaussiano (BT=0.4, ±3 símbolos)
    _BT   = 0.4
    _NTAPS_MATCH = AIS_SPS * 6 + 1  # 49 taps, ventana de ±3 bits

    _FIR_TAPS = 64
    _FIR_FC   = 12500.0  # Hz  — ancho de canal AIS (ITU-R M.1084)

    def __init__(self, sr: int, audio_sr: int):
        self.sr       = sr          # 256 000
        self.audio_sr = audio_sr    # no se usa en AIS; guardado por consistencia

        # Filtro FIR pasabajas IQ (pre-discriminador, fs=256 kHz)
        self._b_fir = signal.firwin(
            self._FIR_TAPS,
            self._FIR_FC,
            pass_zero=True,
            fs=sr,
            window='hamming',
        )
        self._zi_fir = signal.lfilter_zi(self._b_fir, [1.0])

        # Filtro FIR pasabajas post-discriminador (fs=76 800 Hz)
        self._b_lpf = signal.firwin(
            48,
            6000.0,
            pass_zero=True,
            fs=AIS_FS,
            window='hamming',
        )
        self._zi_lpf = signal.lfilter_zi(self._b_lpf, [1.0])
        t_match  = np.arange(-(self._NTAPS_MATCH // 2), self._NTAPS_MATCH // 2 + 1)
        sigma    = np.sqrt(np.log(2)) / (2 * np.pi * self._BT / AIS_SPS)
        h_gauss  = np.exp(-t_match**2 / (2 * sigma**2))
        h_gauss /= h_gauss.sum()
        self._b_match = h_gauss.astype(np.float32)
        self._zi_match = signal.lfilter_zi(self._b_match, [1.0])

        # Estado Gardner TED
        # mu    : fracción de muestra (corrección acumulada, |mu| < 0.5)
        # x_prev: muestra del símbolo anterior (para e[k])
        # _gardner_gain: ganancia del lazo (0.01 es conservador y estable)
        self._mu         = 0.0
        self._x_prev_sym = 0.0
        self._gardner_gain = 0.01

        # Estado NRZI
        self._ultimo_nivel = 0   # nivel físico del símbolo anterior

        # Buffer de bits continuo (HDLC)
        self._bit_buffer: list[int] = []

        # Cola de muestras sobrantes entre bloques
        self._muestra_buffer = np.zeros(0, dtype=np.float32)

    def procesar(self, muestras: np.ndarray) -> list[bytes]:
        """
        Procesa un bloque de muestras IQ crudas.
        Devuelve una lista (posiblemente vacía) de tramas NMEA en bytes.
        """

        # 1. Eliminar offset DC
        muestras = muestras - np.mean(muestras)

        # 2. Filtro FIR pasabajas IQ (pre-discriminador, ±12.5 kHz canal AIS)
        muestras, self._zi_fir = signal.lfilter(
            self._b_fir, [1.0], muestras, zi=self._zi_fir
        )

        # 3. Discriminador FM diferencial
        #    freq_inst[n] = angle( x[n] * conj(x[n-1]) )
        freq_inst = np.angle(muestras[1:] * np.conj(muestras[:-1])).astype(np.float32)

        # 4. Resample a AIS_FS (76 800 Hz) — factor 3/10
        freq_76k = signal.resample_poly(freq_inst, self._UP, self._DOWN).astype(np.float32)

        # 5. Filtro pasabajas post-discriminador (0–6 kHz, rechaza ruido)
        freq_76k, self._zi_lpf = signal.lfilter(
            self._b_lpf, [1.0], freq_76k, zi=self._zi_lpf
        )

        # 6. Filtro matchado gaussiano (estado continuo entre bloques)
        freq_76k, self._zi_match = signal.lfilter(
            self._b_match, [1.0], freq_76k, zi=self._zi_match
        )

        # 7. Concatenar sobrante del bloque anterior
        muestras_proc = np.concatenate([self._muestra_buffer, freq_76k])

        # 8. Gardner TED + decisión de bit + NRZI decode
        bits_nuevos = self._gardner_decode(muestras_proc)

        # 9. Actualizar buffer HDLC y extraer mensajes
        self._bit_buffer.extend(bits_nuevos)
        if len(self._bit_buffer) > 8192:
            self._bit_buffer = self._bit_buffer[-8192:]

        return self._extraer_mensajes()

    def _gardner_decode(self, muestras: np.ndarray) -> list[int]:
        """
        Aplica Gardner TED sobre el vector de muestras y devuelve
        los bits NRZI-decodificados.
        """
        sps    = AIS_SPS          # 8
        mu     = self._mu
        x_prev = self._x_prev_sym
        gain   = self._gardner_gain
        bits   = []

        # Arrancar en el centro del primer símbolo disponible
        i = sps - 1

        while i + sps < len(muestras):
            x_curr = float(muestras[i])
            x_mid  = float(muestras[i - sps // 2])  # muestra a T/2 atrás

            # Error de timing Gardner
            e = x_mid * (np.sign(x_curr) - np.sign(x_prev))

            # Actualizar fracción de muestra (clampeado a ±0.5 para estabilidad)
            mu -= gain * e
            mu  = max(-0.5, min(0.5, mu))

            # Decisión de bit (umbral = 0)
            nivel_actual = 1 if x_curr > 0.0 else 0

            # NRZI decode: un 0 lógico = transición en el nivel físico
            bit_logico = 1 if nivel_actual == self._ultimo_nivel else 0
            self._ultimo_nivel = nivel_actual
            bits.append(bit_logico)

            x_prev = x_curr

            # Avance: sps + corrección entera de mu
            correccion = int(round(mu))
            mu -= correccion
            i  += sps + correccion

        # Guardar estado y sobrante
        self._mu         = mu
        self._x_prev_sym = x_prev
        self._muestra_buffer = muestras[max(0, i - sps + 1):]

        return bits

    def _extraer_mensajes(self) -> list[bytes]:
        """
        Busca tramas HDLC (0x7E ... 0x7E) en el buffer de bits,
        aplica bit-unstuffing y genera cadenas NMEA.
        """
        BANDERA  = [0, 1, 1, 1, 1, 1, 1, 0]
        mensajes = []

        while True:
            # Buscar bandera de inicio
            inicio = -1
            buf    = self._bit_buffer
            for i in range(len(buf) - 7):
                if buf[i:i+8] == BANDERA:
                    inicio = i + 8
                    break
            if inicio == -1:
                break

            # Buscar bandera de fin
            fin = -1
            for i in range(inicio, len(buf) - 7):
                if buf[i:i+8] == BANDERA:
                    fin = i
                    break
            if fin == -1:
                # Mensaje incompleto: conservar desde el inicio de la bandera
                self._bit_buffer = buf[inicio - 8:]
                break

            payload_crudo = buf[inicio:fin]

            # Bit-unstuffing dentro del payload
            payload_limpio = []
            contador_unos  = 0
            saltar         = False
            for dato in payload_crudo:
                if saltar:
                    saltar = False
                    continue
                payload_limpio.append(dato)
                if dato == 1:
                    contador_unos += 1
                    if contador_unos == 5:
                        saltar        = True   # el siguiente 0 fue insertado
                        contador_unos = 0
                else:
                    contador_unos = 0

            nmea = self._bits_a_nmea(payload_limpio)
            if nmea:
                mensajes.append(nmea)

            self._bit_buffer = buf[fin:]

        return mensajes

    # Tabla CRC-CCITT reflejada para verificación FCS HDLC
    # Polinomio 0x1021 reflejado = 0x8408, init=0xFFFF, xorout=0xFFFF
    _CRC_TABLE: list[int] = []

    @classmethod
    def _build_crc_table(cls):
        if cls._CRC_TABLE:
            return
        for i in range(256):
            c = i
            for _ in range(8):
                c = (c >> 1) ^ 0x8408 if (c & 1) else c >> 1
            cls._CRC_TABLE.append(c)

    @classmethod
    def _crc_hdlc(cls, data: bytes) -> int:
        """CRC-CCITT reflejado, como lo usa HDLC/AIS para el FCS."""
        cls._build_crc_table()
        crc = 0xFFFF
        for b in data:
            crc = (crc >> 8) ^ cls._CRC_TABLE[(crc ^ b) & 0xFF]
        return crc ^ 0xFFFF

    def _bits_a_nmea(self, bits: list[int]):
        """
        Convierte payload HDLC (ya sin bit-stuffing) a cadena NMEA !AIVDM.

        """

        MIN_BITS = 40 + 16   # holgura mínima
        MAX_BITS = 1024      # ningún mensaje AIS estándar supera esto

        n = len(bits)
        if n < MIN_BITS or n > MAX_BITS or n % 8 != 0:
            return None

        # Convertir bits a bytes con HDLC LSB-first:

        def bits_a_bytes_lsb(b_list):
            ba = bytearray()
            for i in range(0, len(b_list), 8):
                byte = 0
                for j in range(8):
                    if b_list[i + j]:
                        byte |= (1 << j)   # LSB first
                ba.append(byte)
            return bytes(ba)

        todos_bytes = bits_a_bytes_lsb(bits)

        # Los últimos 2 bytes son el FCS (little-endian en HDLC)
        payload_bytes = todos_bytes[:-2]
        fcs_lo, fcs_hi = todos_bytes[-2], todos_bytes[-1]
        fcs_recibido  = fcs_lo | (fcs_hi << 8)

        # Verificar CRC — el filtro real contra basura
        fcs_calculado = self._crc_hdlc(payload_bytes)
        if fcs_calculado != fcs_recibido:
            return None   # trama corrupta

        payload_bits_total = len(payload_bytes) * 8

        try:
            encoder = SixBitNibleEncoder()
            payload_ascii, fill_bits = encoder.encode(payload_bytes, payload_bits_total)
        except Exception:
            return None

        cuerpo   = f"AIVDM,1,1,,B,{payload_ascii},{fill_bits}"
        checksum = 0
        for c in cuerpo:
            checksum ^= ord(c)

        return f"!{cuerpo}*{checksum:02X}".encode('ascii')

def main():
    print(f"--- NaviWave corriendo en {FRECUENCIA_CENTRAL/1e6} MHz ---")
    sdr = None
    try:
        sdr = ClienteRtlTcpPuro(host=RTL_TCP_HOST, port=RTL_TCP_PORT)
        sdr.sample_rate = SAMPLE_RATE
        sdr.center_freq = FRECUENCIA_CENTRAL
        sdr.gain        = GANANCIA

        proc      = ProcesadorNaviWave(SAMPLE_RATE, AUDIO_RATE)
        squelch   = SquelchAdaptativo(margen_db=8)
        grabador  = GrabadorFondo(AUDIO_RATE)
        proc_ais  = ProcesadorAIS(SAMPLE_RATE, AUDIO_RATE)

        grabando             = False
        bloques_silencio     = 0
        BLOQUES_PARA_CORTAR  = 20
        bloques_silencio_total    = 0
        BLOQUES_PARA_CAMBIAR_AIS  = 175

        bloques_arranque  = 0
        BLOQUES_ARRANQUE  = 20

        bloques_voz              = 0
        BLOQUES_PARA_CAMBIAR_VOZ = 50

        ESTADO_ACTUAL = "VOZ"

        with sd.OutputStream(samplerate=AUDIO_RATE, channels=1, dtype='float32') as stream:
            while True:

                # ── ESTADO VOZ ────────────────────────────────────────
                while ESTADO_ACTUAL == "VOZ":
                    try:
                        raw_samples = sdr.read_samples(16384)
                    except Exception as e:
                        # 2. CAPTURAR ERRORES DE RED ADEMÁS DE USB
                        err_str = str(e).upper()
                        if 'LIBUSB_ERROR' in err_str or "PIPE" in err_str or "CONNECTION" in err_str or "SOCKET" in err_str:
                            print(f"\n[⚠️ WARNING] Desconexión del servidor TCP. Reconectando... ({e})")
                            try:
                                sdr.close()
                            except:
                                pass # Ignorar errores al cerrar un socket ya roto
                            time.sleep(2)
                            
                            # RECONECTAR TCP
                            sdr = ClienteRtlTcpPuro(host=RTL_TCP_HOST, port=RTL_TCP_PORT)
                            sdr.sample_rate = SAMPLE_RATE
                            sdr.center_freq = FRECUENCIA_CENTRAL
                            sdr.gain        = GANANCIA
                            continue
                        else:
                            raise

                    pwr = float(10 * np.log10(np.mean(np.abs(raw_samples)**2) + 1e-12))
                    activa, piso, umbral = squelch.evaluar(pwr)

                    if bloques_arranque < BLOQUES_ARRANQUE:
                        bloques_arranque += 1
                        activa = False

                    if activa:
                        bloques_silencio = 0
                        bloques_silencio_total = 0
                        if not grabando:
                            grabador.iniciar(FRECUENCIA_CENTRAL)
                            grabando = True
                        audio_output = proc.procesar(raw_samples)
                        stream.write(audio_output.reshape(-1, 1))
                        grabador.grabar(audio_output)
                        print(f"\r[VOZ 🟢] Pwr: {pwr:>6.1f} | Piso: {piso:>6.1f} | Umbral: {umbral:>6.1f}  ", end="")
                    else:
                        if grabando:
                            bloques_silencio += 1
                            audio_output = proc.procesar(raw_samples)
                            stream.write(audio_output.reshape(-1, 1))
                            print(f"\r[TAIL 🟡] Esperando... {bloques_silencio}/{BLOQUES_PARA_CORTAR}        ", end="")
                            if bloques_silencio >= BLOQUES_PARA_CORTAR:
                                grabador.detener()
                                grabando = False
                        else:
                            stream.write(np.zeros((3072, 1), dtype='float32'))
                            bloques_silencio_total += 1
                            print(f"\r[--- ⚪] Pwr: {pwr:>6.1f} | Piso: {piso:>6.1f} | Umbral: {umbral:>6.1f}  ", end="")

                    if bloques_silencio_total >= BLOQUES_PARA_CAMBIAR_AIS and not activa:
                        ESTADO_ACTUAL = "SALTANDO_AIS"
                        break

                # ── TRANSICIÓN → AIS ──────────────────────────────────
                if ESTADO_ACTUAL == "SALTANDO_AIS":
                    sdr.center_freq = FRECUENCIA_AIS
                    sdr.gain        = 'auto'
                    for _ in range(3):
                        sdr.read_samples(16384)      # flush del buffer del SDR
                    proc_ais = ProcesadorAIS(SAMPLE_RATE, AUDIO_RATE)   # estado limpio
                    ESTADO_ACTUAL = "AIS"
                    print("\n[🔁 CAMBIO] Frecuencia AIS activa")

                # ── ESTADO AIS ────────────────────────────────────────
                while ESTADO_ACTUAL == "AIS":
                    db_gestor = GestorBaseDatos()
                    stream.write(np.zeros((3072, 1), dtype='float32'))
                    raw_samples = sdr.read_samples(16384)

                    lista_mensajes = proc_ais.procesar(raw_samples)

                    for mensaje in lista_mensajes:
                        print(f"\n[RAW ] {mensaje.decode('ascii', errors='replace')}")
                        try:
                            barco = decode(mensaje)
                            mmsi  = getattr(barco, 'mmsi', None)
                            mtype = getattr(barco, 'msg_type', '?')

                            # Mostrar cualquier mensaje que pyais decodifique sin excepción.
                            # Sin filtros de MMSI ni geográficos: aceptamos todo lo que llega.
                            lat  = getattr(barco, 'lat',      None)
                            lon  = getattr(barco, 'lon',      None)
                            name = getattr(barco, 'shipname', None)
                            sog  = getattr(barco, 'speed',    None)

                            objBarco = {"tipo": mtype,
                                        "mmsi": mmsi,
                                        "nombre": name,
                                        "lat": lat,
                                        "lon": lon}
                            
                            if len(str(mmsi)) == 9:
                                db_gestor.saveBarcos(objBarco)

                            partes = [f"tipo={mtype}", f"MMSI={mmsi}"]
                            if lat  is not None: partes.append(f"Lat={lat:.4f}")
                            if lon  is not None: partes.append(f"Lon={lon:.4f}")
                            if name and name.strip(): partes.append(f"Nombre={name.strip()}")
                            if sog  is not None: partes.append(f"SOG={sog}kn")

                            print(f"[DECO] {' | '.join(partes)}")

                        except Exception as e:
                            print(f"[ERR BARCO] {type(e).__name__}: {e}")

                    #bloques_voz += 1
                    if bloques_voz >= BLOQUES_PARA_CAMBIAR_VOZ:
                        ESTADO_ACTUAL = "VOZ"
                        break

                # Reset de contadores al volver a VOZ
                bloques_arranque       = 0
                bloques_voz            = 0
                bloques_silencio       = 0
                bloques_silencio_total = 0
                sdr.center_freq        = FRECUENCIA_CENTRAL
                sdr.gain               = GANANCIA

    except KeyboardInterrupt:
        print("\n\n[INFO] Detenido por el usuario.")
        if 'grabador' in locals() and grabando:
            grabador.detener()
    except Exception as e:
        print(f"\n[ERROR SDR] {e}")
    finally:
        if sdr:
            sdr.close()
            print("[OK] SDR cerrado correctamente.")


if __name__ == "__main__":
    main()