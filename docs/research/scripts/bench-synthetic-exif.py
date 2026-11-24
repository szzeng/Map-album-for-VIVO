"""Benchmark first-pass and incremental indexing with generated JPEG files only."""

import argparse
import io
import platform
import random
import sqlite3
import tempfile
import time
from pathlib import Path

import PIL
from PIL import Image


class CountedInput(io.BufferedReader):
    bytes_read = 0

    def read(self, size=-1):
        data = super().read(size)
        self.bytes_read += len(data)
        return data


def synthetic_jpeg(include_gps):
    pixels = random.Random(1).randbytes(320 * 240)
    image = Image.frombytes('L', (320, 240), pixels)
    exif = Image.Exif()
    if include_gps:
        exif[34853] = {1: 'N', 2: (1, 2, 3), 3: 'E', 4: (4, 5, 6)}
    output = io.BytesIO()
    image.save(output, 'JPEG', quality=70, exif=exif)
    return output.getvalue()


def parse_gps(file):
    with CountedInput(file.open('rb', buffering=0)) as stream:
        with Image.open(stream) as image:
            gps = image.getexif().get_ifd(34853)
        if gps:
            latitude = dms_to_degrees(gps[2], gps[1])
            longitude = dms_to_degrees(gps[4], gps[3])
        else:
            latitude = longitude = None
        return latitude, longitude, stream.bytes_read


def dms_to_degrees(parts, hemisphere):
    degrees, minutes, seconds = map(float, parts)
    sign = -1 if hemisphere in ('S', 'W') else 1
    return sign * (degrees + minutes / 60 + seconds / 3600)


def run_pass(label, db, directory, media_ids, generation):
    wall_start = time.perf_counter()
    cpu_start = time.process_time()
    read = parsed = skipped = geotagged = 0
    for media_id in media_ids:
        old = db.execute('SELECT generation FROM gps WHERE id=?', (media_id,)).fetchone()
        if old == (generation,):
            skipped += 1
            continue
        lat, lon, count = parse_gps(directory / f'{media_id}.jpg')
        parsed += 1
        read += count
        geotagged += lat is not None
        db.execute('INSERT OR REPLACE INTO gps VALUES (?, ?, ?, ?)', (media_id, generation, lat, lon))
        if parsed % 256 == 0:
            db.commit()
    db.commit()
    print(f'{label}: wall_s={time.perf_counter()-wall_start:.4f} cpu_s={time.process_time()-cpu_start:.4f} '
          f'parsed={parsed} skipped={skipped} gps={geotagged} logical_read_bytes={read}', flush=True)
    return parsed, skipped, geotagged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--count', type=int, default=10000)
    args = parser.parse_args()
    if args.count < 100:
        parser.error('--count must be at least 100')
    with tempfile.TemporaryDirectory(prefix='map001-synthetic-') as temporary:
        directory = Path(temporary)
        fixtures = [synthetic_jpeg(False), synthetic_jpeg(True)]
        print(f'{platform.system()} {platform.machine()} Python {platform.python_version()} '
              f'Pillow {PIL.__version__} SQLite {sqlite3.sqlite_version}', flush=True)
        print(f'fixture_count={args.count} jpeg_bytes={list(map(len, fixtures))} '
              'gps_ratio=1/5 batch=256 readers=1 cache=recently_written no_pixel_decode', flush=True)
        for media_id in range(args.count):
            (directory / f'{media_id}.jpg').write_bytes(fixtures[media_id % 5 == 0])
        db_path = directory / 'index.sqlite'
        db = sqlite3.connect(db_path)
        try:
            db.execute('CREATE TABLE gps (id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, lat REAL, lon REAL)')
            assert run_pass('initial', db, directory, range(args.count), 1) == (
                args.count, 0, (args.count + 4) // 5)
            assert run_pass('visible_metadata_reconcile', db, directory, range(args.count), 1) == (0, args.count, 0)
            assert run_pass('delta_100_modified', db, directory, range(100), 2) == (100, 0, 20)
            print(f'sqlite_file_bytes={db_path.stat().st_size}', flush=True)
        finally:
            db.close()


if __name__ == '__main__':
    main()
