"""Generate the small highlight grains using only the Python standard library."""
from pathlib import Path
import random
import struct
import zlib

width, height = 1024, 384
random_source = random.Random(20261001)
alpha = round(255 * (141 / 255) ** 2 * 0.6)
pixels = bytearray(width * height * 4)
for y in range(0, height, 2):
    for x in range(0, width, 2):
        opacity = alpha if random_source.random() < 0.22 else 0
        for dy in range(2):
            for dx in range(2):
                offset = ((y + dy) * width + x + dx) * 4
                pixels[offset:offset + 4] = bytes((255, 255, 255, opacity))

def chunk(kind, content):
    return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content))

rows = b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
target = Path(__file__).resolve().parents[2] / 'entry/src/main/resources/rawfile/press_grain.png'
target.write_bytes(png)
print(f'{target.name}: {len(png)} bytes')
