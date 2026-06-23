import zipfile
import os
import shutil
import sys
import tempfile


def make_unsigned_ipa(input_path, output_path=None):
    """
    Strip code signature and provisioning profile from an IPA file.
    Produces a truly unsigned IPA that Sideloadly/AltStore can re-sign.
    """
    if not os.path.isfile(input_path):
        print(f"Error: File not found: {input_path}")
        return False

    if output_path is None:
        base, ext = os.path.splitext(input_path)
        output_path = f"{base}-unsigned{ext}"

    print(f"Input:  {input_path}")
    print(f"Output: {output_path}")
    print("-" * 50)

    # Create temp directory
    temp_dir = tempfile.mkdtemp(prefix="ipa_unsigned_")
    try:
        # Extract IPA
        print("Extracting IPA...")
        with zipfile.ZipFile(input_path, 'r') as zip_ref:
            zip_ref.extractall(temp_dir)

        # Find the .app bundle inside Payload
        payload_dir = os.path.join(temp_dir, "Payload")
        if not os.path.isdir(payload_dir):
            print("Error: Invalid IPA - missing Payload/ directory")
            return False

        app_dirs = [d for d in os.listdir(payload_dir) if d.endswith(".app")]
        if not app_dirs:
            print("Error: Invalid IPA - missing .app bundle inside Payload/")
            return False

        app_path = os.path.join(payload_dir, app_dirs[0])
        print(f"Found app bundle: {app_dirs[0]}")

        # Remove _CodeSignature directory
        code_sig_path = os.path.join(app_path, "_CodeSignature")
        if os.path.isdir(code_sig_path):
            shutil.rmtree(code_sig_path)
            print("Removed: _CodeSignature/")
        else:
            print("No _CodeSignature directory found (already unsigned)")

        # Remove embedded provisioning profile
        prov_path = os.path.join(app_path, "embedded.mobileprovision")
        if os.path.isfile(prov_path):
            os.remove(prov_path)
            print("Removed: embedded.mobileprovision")
        else:
            print("No embedded.mobileprovision found")

        # Note about embedded binary signature (codesign --remove-signature)
        # On macOS we could strip LC_CODE_SIGNATURE from the Mach-O binary,
        # but on Windows we can't. For Sideloadly, removing _CodeSignature
        # is usually sufficient.
        binary_name = app_dirs[0].replace(".app", "")
        binary_path = os.path.join(app_path, binary_name)
        if os.path.isfile(binary_path):
            print(f"App binary found: {binary_name}")
            print("Note: On macOS, run 'codesign --remove-signature' on the binary")
            print("      for a fully stripped signature. On Windows, removing")
            print("      _CodeSignature is usually enough for Sideloadly.")
        else:
            print("Warning: Expected binary not found inside .app")

        # Repackage as IPA
        print("Repackaging IPA...")
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for root, dirs, files in os.walk(temp_dir):
                for file in files:
                    file_path = os.path.join(root, file)
                    arcname = os.path.relpath(file_path, temp_dir)
                    zipf.write(file_path, arcname)

        print("-" * 50)
        print("SUCCESS!")
        print(f"Unsigned IPA saved to: {os.path.abspath(output_path)}")
        return True

    except Exception as e:
        print(f"Error: {e}")
        return False
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    print("=" * 60)
    print("IPA Unsigned Converter")
    print("Strips code signature so Sideloadly/AltStore can re-sign it.")
    print("=" * 60)

    if len(sys.argv) > 1:
        input_path = sys.argv[1]
    else:
        input_path = input("Enter the path to the IPA file: ").strip().strip('"')

    if not input_path:
        print("No input file provided.")
        return

    output_path = None
    if len(sys.argv) > 2:
        output_path = sys.argv[2]

    make_unsigned_ipa(input_path, output_path)


if __name__ == "__main__":
    main()
