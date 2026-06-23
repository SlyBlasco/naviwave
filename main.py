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
from rtlsdr import RtlSdr
import sounddevice as sd
import soundfile as sf
import threading
import queue
import time
from datetime import datetime
from pyais import decode
from pyais.util import SixBitNibleEncoder

# --- PARÁMETROS ---
#156_800_000 es la frecuencia del puerto.
FRECUENCIA_CENTRAL = 156_800_000
FRECUENCIA_AIS = 162_025_000
SAMPLE_RATE = 256_000
GANANCIA = 15 # El valor que hace que entre el audio sin ruido electromagnético.             
AUDIO_RATE = 48_000


class GrabadorFondo:
    def __init__(self, sample_rate):
        self.sr = sample_rate
        self.cola = queue.Queue()
        self.archivo_actual = None
        self.grabaciones_dir = Path(__file__).parent / "grabaciones"
        self.grabaciones_dir.mkdir(parents=True, exist_ok=True)
        
        # Iniciamos el trabajador de fondo
        self.hilo = threading.Thread(target=self._trabajador, daemon=True)
        self.hilo.start()

    def iniciar(self, frecuencia):
        # Genera un nombre basado en timestamp (Ej: NaviWave_157.550MHz_20260511_143000.wav)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        nombre = f"NaviWave_{frecuencia/1e6:.3f}MHz_{timestamp}.wav"
        self.cola.put(("INICIAR", nombre))

    def grabar(self, audio):
        self.cola.put(("AUDIO", audio))

    def detener(self):
        self.cola.put(("DETENER", None))

    def _trabajador(self):
        while True:
            comando, payload = self.cola.get()
            
            if comando == "INICIAR":
                if self.archivo_actual is None:
                    # Abrimos el archivo en modo escritura
                    # Ensure the recordings directory exists and open the file inside it
                    self.ruta = str(self.grabaciones_dir / payload)
                    self.archivo_actual = sf.SoundFile(self.ruta, mode='w', samplerate=self.sr, channels=1)
                    print(f"\n[REC 🔴] Grabando en: {payload}")
                    
            elif comando == "AUDIO":
                if self.archivo_actual is not None:
                    self.archivo_actual.write(payload)
                    
            elif comando == "DETENER":
                if self.archivo_actual is not None:
                    self.archivo_actual.close()
                    self.archivo_actual = None
                    print("\n[REC ⏹️] Archivo guardado correctamente.")
                    
            self.cola.task_done()


class SquelchAdaptativo:
    def __init__(self, margen_db, piso_inicial=-45.0):
        self.piso_ruido = piso_inicial
        self.margen = margen_db
        self.alpha_bajada = 0.1    
        self.alpha_subida = 0.05   
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
        self.sr = sr
        self.audio_sr = audio_sr
        self.b_chan, self.a_chan = signal.butter(4, 12500/(sr/2), btype='low')
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
        audio_final, self.zi_deemph = signal.lfilter(self.b_deemph, self.a_deemph, audio_resampled, zi=self.zi_deemph)
        limite = np.max(np.abs(audio_final))
        if limite > 0:
            audio_final /= limite
        return audio_final.astype(np.float32) * 0.4

class ProcesadorAIS:
    def __init__(self, sr, audio_sr):
        self.sr = sr
        self.audio_sr = audio_sr
        self.b_chan, self.a_chan = signal.butter(4, 12500/(sr/2), btype='low')
        self.zi_chan = signal.lfilter_zi(self.b_chan, self.a_chan)\
        
        self.ultimo_bit_nrzi = 0
        self.contador_unos = 0

        self.fase_reloj = 2.5  # Empezamos a la mitad del bit (el centro ideal)
        self.ultima_muestra_audio = 0.0 # Para detectar el cruce por cero entre bloques

        self.bit_buffer = []

    def procesar(self, muestras):
        # 1. Demodulación FM y Filtro
        muestras -= np.mean(muestras)
        muestras, self.zi_chan = signal.lfilter(self.b_chan, self.a_chan, muestras, zi=self.zi_chan)
        audio = np.angle(muestras[1:] * np.conj(muestras[:-1]))
        audio_resampled = signal.resample_poly(audio, self.audio_sr, self.sr)
        
        # 2. CLOCK RECOVERY (Metrónomo Inteligente) Y DECO NRZI Inmediato
        lista_nrzi = []
        muestras_por_bit = 5.0
        
        for muestra_actual in audio_resampled:
            # Detectar la frontera física entre bits (cruce por cero)
            cruce_por_cero = (self.ultima_muestra_audio < 0 and muestra_actual >= 0) or \
                             (self.ultima_muestra_audio >= 0 and muestra_actual < 0)
            
            if cruce_por_cero:
                error_fase = self.fase_reloj - (muestras_por_bit / 2.0)
                self.fase_reloj -= error_fase * 0.25 # Ajuste PLL
            
            self.fase_reloj -= 1.0
            
            if self.fase_reloj <= 0:
                # Extraemos el dato bruto
                bit_bruto = 1 if muestra_actual >= 0 else 0
                # Deshacemos el NRZI en tiempo real comparando con el bit pasado
                bit_nrzi = 1 if bit_bruto == self.ultimo_bit_nrzi else 0
                self.ultimo_bit_nrzi = bit_bruto
                
                lista_nrzi.append(bit_nrzi)
                self.fase_reloj += muestras_por_bit
            
            self.ultima_muestra_audio = muestra_actual

        # 3. Guardar en el buffer continuo
        self.bit_buffer.extend(lista_nrzi)
        
        # Prevenir desbordamiento de RAM por ruido infinito
        if len(self.bit_buffer) > 4000:
            self.bit_buffer = self.bit_buffer[-4000:]
            
        # 4. Extraer mensajes completos (Protocolo HDLC)
        return self.extraer_mensajes()

    def extraer_mensajes(self):
        mensajes_encontrados = []
        bandera = [0, 1, 1, 1, 1, 1, 1, 0] # 0x7E - HDLC Flag
        
        while True:
            # 1. Encontrar la bandera de INICIO
            inicio = -1
            for i in range(len(self.bit_buffer) - 7):
                if self.bit_buffer[i:i+8] == bandera:
                    inicio = i + 8
                    break
            
            if inicio == -1:
                break # No hay inicio, esperamos más datos del SDR
                
            # 2. Encontrar la bandera de FIN
            fin = -1
            for i in range(inicio, len(self.bit_buffer) - 7):
                if self.bit_buffer[i:i+8] == bandera:
                    fin = i
                    break
            
            if fin == -1:
                # El mensaje empezó pero se cortó el bloque. Lo guardamos para la otra vuelta.
                self.bit_buffer = self.bit_buffer[inicio-8:]
                break
                
            # 3. Extraer solo el PAYLOAD
            payload_crudo = self.bit_buffer[inicio:fin]
            
            # 4. BIT STUFFING (Desescombro) - Se aplica SOLO dentro del Payload
            payload_limpio = []
            contador_unos = 0
            ignorar_siguiente = False
            
            for dato in payload_crudo:
                if ignorar_siguiente:
                    ignorar_siguiente = False
                    continue
                    
                if dato == 1:
                    payload_limpio.append(1)
                    contador_unos += 1
                    if contador_unos == 5:
                        ignorar_siguiente = True # El siguiente 0 fue insertado por el barco, se ignora
                        contador_unos = 0
                else:
                    payload_limpio.append(0)
                    contador_unos = 0
            
            # 5. Convertir a NMEA Nativos
            nmea = self.decodificar_a_nmea(payload_limpio)
            if nmea:
                mensajes_encontrados.append(nmea)
            
            # Recortamos el buffer y seguimos buscando más mensajes
            self.bit_buffer = self.bit_buffer[fin:]
            
        return mensajes_encontrados

    def decodificar_a_nmea(self, payload_bits):
        total_bits = len(payload_bits)
        # Un mensaje AIS estándar no puede tener menos de 30 bits, si los tiene, era ruido
        if total_bits < 30:
            return None

        bytes_array = bytearray()
        for i in range(0, total_bits, 8):
            bloque_8 = payload_bits[i:i+8]
            str_binario = "".join(str(b) for b in bloque_8)
            str_binario = str_binario.ljust(8, '0')
            bytes_array.append(int(str_binario, 2))
        
        datos_binarios = bytes(bytes_array)

        encoder = SixBitNibleEncoder()
        payload_ascii, fill_bits = encoder.encode(datos_binarios, total_bits)

        cuerpo = f"AIVDM,1,1,,B,{payload_ascii},{fill_bits}"

        checksum = 0
        for caracter in cuerpo:
            checksum ^= ord(caracter)
        checksum_hex = f"{checksum:02X}"

        return f"!{cuerpo}*{checksum_hex}".encode('ascii')







def main():
    print(f"--- NaviWave Corriendo en {FRECUENCIA_CENTRAL/1e6} MHz ---")
    sdr = None
    try:
        sdr = RtlSdr()
        sdr.sample_rate = SAMPLE_RATE
        sdr.center_freq = FRECUENCIA_CENTRAL
        sdr.gain = GANANCIA

        proc = ProcesadorNaviWave(SAMPLE_RATE, AUDIO_RATE)
        squelch = SquelchAdaptativo(margen_db=6)
        grabador = GrabadorFondo(AUDIO_RATE)
        proc_ais = ProcesadorAIS(SAMPLE_RATE, AUDIO_RATE)
        
        # Variables para controlar la grabación y el "Squelch Tail"
        grabando = False
        bloques_silencio = 0
        BLOQUES_PARA_CORTAR = 15 # Aprox 1 segundo de delay antes de cortar el archivo
        bloques_silencio_total = 0
        BLOQUES_PARA_CAMBIAR_AIS = 150

        bloques_arranque = 0
        BLOQUES_ARRANQUE = 20 # Ignorar los primeros bloques para estabilizar el squelch

        bloques_voz = 0
        BLOQUES_PARA_CAMBIAR_VOZ = 50

        #Variables para controlar el estado 
        ESTADO_ACTUAL = "VOZ"

        #MAQUINA ESTADOS
        #ESTADO MONITOREO VOZ
        with sd.OutputStream(samplerate=AUDIO_RATE, channels=1, dtype='float32') as stream:
            while True:
                while ESTADO_ACTUAL == "VOZ":
                    try:
                        raw_samples = sdr.read_samples(16384)
                    except Exception as e:
                        if 'LIBUSB_ERROR' in str(e) or "PIPE" in str(e).upper():
                            print("\n[⚠️ WARNING] Desconexión temporal del USB. Intentando reconectar...")
                            sdr.close()
                            time.sleep(2)
                            sdr = RtlSdr()
                            sdr.sample_rate = SAMPLE_RATE
                            sdr.center_freq = FRECUENCIA_CENTRAL
                            sdr.gain = GANANCIA
                            continue
                        else:
                            raise e

                    pwr = float(10 * np.log10(np.mean(np.abs(raw_samples)**2) + 1e-12))
                    
                    activa, piso, umbral = squelch.evaluar(pwr)
                    
                    if bloques_arranque < BLOQUES_ARRANQUE:
                        bloques_arranque += 1
                        activa = False  # Forzamos silencio durante el arranque
                    
                    # MÁQUINA DE ESTADOS DE GRABACIÓN
                    if activa:
                        bloques_silencio = 0 # Reiniciamos el contador de silencio
                        if not grabando:
                            grabador.iniciar(FRECUENCIA_CENTRAL)
                            grabando = True
                            
                        audio_output = proc.procesar(raw_samples)
                        stream.write(audio_output.reshape(-1, 1))
                        grabador.grabar(audio_output)
                        print(f"\r[VOZ 🟢] Pwr: {pwr:>6.1f} | Piso: {piso:>6.1f} | Umbral: {umbral:>6.1f}  ", end="") 
                    else:
                        if grabando:
                            # Si estábamos grabando, le damos un tiempo de gracia ("Squelch Tail")
                            bloques_silencio += 1
                            audio_output = proc.procesar(raw_samples)
                            stream.write(audio_output.reshape(-1, 1))
                            #grabador.grabar(audio_output)
                            print(f"\r[TAIL 🟡] Esperando... {bloques_silencio}/{BLOQUES_PARA_CORTAR}        ", end="")
                            
                            if bloques_silencio >= BLOQUES_PARA_CORTAR:
                                grabador.detener()
                                grabando = False
                        else:
                            # Silencio total
                            stream.write(np.zeros((3072, 1), dtype='float32'))
                            bloques_silencio_total += 1
                            print(f"\r[--- ⚪] Pwr: {pwr:>6.1f} | Piso: {piso:>6.1f} | Umbral: {umbral:>6.1f}  ", end="")

                    if bloques_silencio_total >= BLOQUES_PARA_CAMBIAR_AIS and not activa:
                        ESTADO_ACTUAL = "SALTANDO_AIS"
                        break

                #ESTADO SALTANDO A AIS
                if ESTADO_ACTUAL == "SALTANDO_AIS":
                    sdr.center_freq = FRECUENCIA_AIS
                    sdr.gain = 'auto'
                    for _ in range(3):
                        sdr.read_samples(16384)
                    ESTADO_ACTUAL = "AIS"
                    print("\n[🔁 CAMBIO] Cambio hacia frecuencia AIS")

                #ESTADO CAPTURA AIS    
                while ESTADO_ACTUAL == "AIS":
                    stream.write(np.zeros((3072, 1), dtype='float32'))
                    raw_samples = sdr.read_samples(16384)
                    
                    lista_mensajes = proc_ais.procesar(raw_samples)
                    
                    for mensaje in lista_mensajes:
                        try:
                            barco = decode(mensaje)
                            mmsi_str = str(barco.mmsi)
                            
                            if len(mmsi_str) == 9:
                                if mmsi_str[0] in ['2', '3', '7']:
                                    
                                    if hasattr(barco, 'lat') and hasattr(barco, 'lon') and barco.lat is not None:
                                        lat = barco.lat
                                        lon = barco.lon
                                        
                                        if (26.0 <= lat <= 30.0) and (-112.0 <= lon <= -108.0):
                                            print(f"\n[🚢 BARCO REAL] MMSI: {barco.mmsi} | Lat: {lat:.5f}, Lon: {lon:.5f}")
                                            
                                    elif hasattr(barco, 'shipname') and barco.shipname:
                                        nombre = barco.shipname.strip()
                                        if len(nombre) > 2 and bool(re.match(r'^[A-Z0-9\s]+$', nombre)):
                                            print(f"\n[📋 INFO BARCO] MMSI: {barco.mmsi} | Nombre: {nombre}")

                        except Exception:
                            pass


                    bloques_voz += 1
                    if bloques_voz >= BLOQUES_PARA_CAMBIAR_VOZ:
                        ESTADO_ACTUAL = "VOZ"
                        break

                #BLOQUE PARA REINCIAR BLOQUES
                bloques_arranque = 0
                bloques_voz = 0
                bloques_silencio = 0
                bloques_silencio_total = 0
                sdr.center_freq = FRECUENCIA_CENTRAL
                sdr.gain = GANANCIA




    except KeyboardInterrupt:
        print("\n\n[INFO] Detenido por el usuario.")
        if 'grabador' in locals() and grabando:
            grabador.detener()
    except Exception as e:
        print(f"\n[ERROR] {e}")
    finally:
        if sdr:
            sdr.close()
            print("[OK] SDR cerrado correctamente.")

if __name__ == "__main__":
    main()