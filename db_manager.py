import sqlite3
from pathlib import Path

class GestorBaseDatos:
    def __init__(self):
        self.connection = sqlite3.connect('naviwave.db')
        try:
            with self.connection:
                cursor = self.connection.cursor()

                cursor.execute("""    
                        CREATE TABLE IF NOT EXISTS audios (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            nombre TEXT NOT NULL,
                            ruta_archivo TEXT NOT NULL,
                            fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP          
                        )               
                """)

                cursor.execute("""
                        CREATE TABLE IF NOT EXISTS barcos (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            mmsi TEXT,
                            nombre TEXT,
                            lat REAL,
                            lon REAL,
                            fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                """)

                self.connection.commit()
        except sqlite3.Error as e:
            print(f"[SQL ERR] {e}")
        finally:
            self.connection.close()

    # ----- VOZ ------
    def guardarVoz(self, nombre, ruta):
        """Guarda el nombre y ruta de cada audio en la tabla audios."""

        self.ruta = str(ruta / nombre)
        self.connection = sqlite3.connect('naviwave.db')
        try:
            with self.connection:
                cursor = self.connection.cursor()

                cursor.execute('''
                    INSERT INTO audios (nombre, ruta_archivo) VALUES (?,?)
                ''', (nombre, self.ruta))

                self.connection.commit()

        except sqlite3.Error as e:
            print(f"[SQL ERR] {e}")
        finally:
            self.connection.close()

    def listarAudios(self):
        """Devuelve y muestra todos los registros de la tabla audios."""
        try:
            conn = sqlite3.connect('naviwave.db')
            cursor = conn.cursor()
            
            cursor.execute('SELECT id, nombre, fecha_creacion FROM audios ORDER BY fecha_creacion DESC')
            filas = cursor.fetchall()
            
            print(f"\n{'ID':<5} {'Nombre':<40} {'Fecha':<19}")
            print("─" * 65)
            for fila in filas:
                print(f"{fila[0]:<5} {fila[1]:<40} {fila[2]:<19}")
            
            conn.close()
            return filas
        except sqlite3.Error as e:
            print(f"[SQL ERR] {e}")
            return None
        
    # ----- AIS / BARCOS ------

    def saveBarcos(self, barco):
        """Registra un barco con todas sus atributos"""
        # acepta barco como tupla/lista (mmsi, nombre, lat, lon) o dict con esas claves
        if isinstance(barco, dict):
            mmsi = barco.get('mmsi')
            nombre = barco.get('nombre')
            lat = barco.get('lat')
            lon = barco.get('lon')
        else:
            mmsi, nombre, lat, lon = barco

        try:
            conn = sqlite3.connect('naviwave.db')
            cursor = conn.cursor()

            cursor.execute('INSERT INTO barcos (mmsi, nombre, lat, lon) VALUES (?,?,?,?)',
                           (mmsi, nombre, lat, lon))
            conn.commit()
        except sqlite3.Error as e:
            print(f"[SQL ERR] {e}")
        finally:
            conn.close()

    def listarBarcos(self):
        """Devuelve y muestra todos los registros de la tabla barcos."""
        try:
            conn = sqlite3.connect('naviwave.db')
            cursor = conn.cursor()
            
            cursor.execute('SELECT id, mmsi, nombre, lat, lon FROM barcos ORDER BY fecha_creacion DESC')
            filas = cursor.fetchall()
            
            print(f"\n{'ID':<5} {'MMSI':<19} {'Lat':<30} {'Lon':<30}")
            print("─" * 65)
            for fila in filas:
                print(f"{fila[0]:<5} {fila[1]:<19} {fila[3]:<30} {fila[4]:<30}")

            conn.close()
            return filas
        except sqlite3.Error as e:
            print(f"[SQL ERR] {e}")
            return None
        
listarAudios = GestorBaseDatos().listarBarcos
print(listarAudios())
