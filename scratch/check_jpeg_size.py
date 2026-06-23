import struct

def main():
    path = 'android-client/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png'
    with open(path, 'rb') as f:
        data = f.read()
    
    # Parse JPEG SOF markers
    # JPEG format contains segments starting with 0xFF followed by a marker byte.
    # SOF0 (Start of Frame, Baseline DCT) marker is 0xC0.
    # SOF2 (Start of Frame, Progressive DCT) marker is 0xC2.
    idx = 2  # Skip SOI marker (FF D8)
    size = None
    while idx < len(data) - 8:
        if data[idx] == 0xFF:
            marker = data[idx+1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                # Found SOF marker
                # Length is 2 bytes after marker (idx+2, idx+3)
                # Precision is idx+4
                # Height is 2 bytes (idx+5, idx+6)
                # Width is 2 bytes (idx+7, idx+8)
                h, w = struct.unpack('>HH', data[idx+5:idx+9])
                size = (w, h)
                break
            else:
                # Read segment length and skip it
                length = struct.unpack('>H', data[idx+2:idx+4])[0]
                idx += 2 + length
        else:
            idx += 1
            
    print("JPEG dimensions:", size)

if __name__ == '__main__':
    main()
