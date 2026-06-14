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
                    for _ in range(3):
                        sdr.read_samples(16384)
                    ESTADO_ACTUAL = "AIS"
                    print("\n[⚠️ WARNING] Cambio hacia frecuencia AIS")

                #ESTADO CAPTURA AIS    
                while ESTADO_ACTUAL == "AIS":
                    stream.write(np.zeros((3072, 1), dtype='float32'))

                    raw_samples = sdr.read_samples(16384)

                    bloques_voz += 1
                    if bloques_voz >= BLOQUES_PARA_CAMBIAR_VOZ:
                        ESTADO_ACTUAL = "VOZ"
                        break

                #BLOQUE PARA REINCIAR BLOQUES
                bloques_arraque = 0
                bloques_voz = 0
                bloques_silencio = 0
                bloques_silencio_total = 0
                sdr.center_freq = FRECUENCIA_CENTRAL




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