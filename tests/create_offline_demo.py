"""Generate an original synthetic raster MBTiles fixture, never geographic tiles.

No network access, third-party map sources, user data or Pillow dependency.
The example covers the world only so any GPS point can be displayed on a test grid.
"""
import argparse
import sqlite3
import struct
import zlib
from pathlib import Path


def png_tile(x, y, z):
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)
    rows = bytearray()
    for py in range(256):
        rows.append(0)
        for px in range(256):
            grid = px % 32 < 2 or py % 32 < 2
            cross = abs(px - 128) < 3 or abs(py - 128) < 3
            color = (105, 85, 180) if cross else (200, 200, 220) if grid else (245 - x * 4, 240 - y * 4, 250 - z * 4)
            rows.extend(color)
    header = struct.pack('>IIBBBBB', 256, 256, 8, 2, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    # Never overwrite an existing file accidentally.
    if args.output.exists():
        raise SystemExit('El archivo de destino ya existe')
    with sqlite3.connect(args.output) as db:
        db.executescript('CREATE TABLE metadata(name TEXT,value TEXT);'
                         'CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB);'
                         'CREATE UNIQUE INDEX tile_index ON tiles(zoom_level,tile_column,tile_row);')
        db.executemany('INSERT INTO metadata VALUES (?,?)', [
            ('name', 'DEMO SINTETICA - NO ES UN MAPA REAL'), ('format', 'png'),
            ('attribution', 'Cuadricula original de TALLEDO FAMILY. Solo prueba tecnica; sin calles ni datos personales.'),
            ('minzoom', '0'), ('maxzoom', '3'), ('bounds', '-180,-85,180,85'), ('center', '0,0,0')])
        for z in range(4):
            n = 1 << z
            for x in range(n):
                for y in range(n):
                    db.execute('INSERT INTO tiles VALUES (?,?,?,?)', (z, x, n - 1 - y, png_tile(x, y, z)))
    print(f'Mapa sintetico generado: {args.output.name}')


if __name__ == '__main__':
    main()
