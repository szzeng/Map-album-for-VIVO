"""List targeted static anchors in installed package APK copies."""

import argparse
import re
import struct
from pathlib import Path
from zipfile import ZipFile


def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def dex_strings(data):
    count, start = struct.unpack_from('<II', data, 56)
    for (offset,) in struct.iter_unpack('<I', data[start:start + count * 4]):
        # Skip the string's ULEB128 UTF-16 length, then read its MUTF-8 bytes.
        while data[offset] >= 128:
            offset += 1
        offset += 1
        end = data.index(0, offset)
        yield data[offset:end].replace(b'\xc0\x80', b'\x00').decode('utf-8', 'replace')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', nargs='+')
    parser.add_argument('--classes', default=r'footprint|mapalbum|albummap|gallery.*(?:map|geographic|geocoder|gps|location)|Gallery(?:Open|Close|Media)Provider|GalleryDataProvider|MediaExtraProvider|FileMetaProvider')
    parser.add_argument('--strings', default=r'footprint|mapalbum|albummap|足迹地图|足迹相册|拍摄地点|CREATE TABLE.*(?:latitude|longitude|location|gps)|(?:gps|location|place|geo)(?:_info|_index|_table|_db)')
    args = parser.parse_args()
    class_pattern = re.compile(args.classes, re.IGNORECASE)
    string_pattern = re.compile(args.strings, re.IGNORECASE)
    for path in args.apk:
        with ZipFile(path) as archive:
            for name in archive.namelist():
                if not re.fullmatch(r'classes\d*\.dex', name):
                    continue
                data = archive.read(name)
                strings = list(dex_strings(data))
                _, types = struct.unpack_from('<II', data, 64)
                count, start = struct.unpack_from('<II', data, 96)
                print(f'[{Path(path).name} / {name}]')
                for offset in range(start, start + count * 32, 32):
                    descriptor = strings[u32(data, types + u32(data, offset) * 4)]
                    if class_pattern.search(descriptor):
                        print('class ', descriptor)
                for value in strings:
                    if not value.startswith('L') or not value.endswith(';'):
                        if string_pattern.search(value):
                            print('string', repr(value))


if __name__ == '__main__':
    main()
