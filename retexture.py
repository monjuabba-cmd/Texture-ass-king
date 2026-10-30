"""Replace EVERY Texture2D in a Unity APK with one image, then rebuild the APK (unsigned)."""
import os, sys, zipfile
import UnityPy
from UnityPy.enums import TextureFormat
from PIL import Image

apk_in, img_path, apk_out = sys.argv[1:4]
WORK = "work"
src = Image.open(img_path).convert("RGBA").resize((256, 256))


def candidate(n):
    b = os.path.basename(n)
    if not n.startswith("assets/") or b.endswith(".resS"):
        return False
    return (b.endswith((".assets", ".unity3d", ".bundle")) or b == "globalgamemanagers"
            or b.startswith(("level", "sharedassets")))


zin = zipfile.ZipFile(apk_in)
names = [i.filename for i in zin.infolist() if candidate(i.filename)]
print(f"{len(names)} candidate Unity files")

changed, total = {}, 0
for n in names:
    path = zin.extract(n, WORK)
    try:
        env = UnityPy.load(path)
    except Exception as e:
        print("skip", n, e)
        continue
    count = 0
    for obj in env.objects:
        if obj.type.name != "Texture2D":
            continue
        try:
            t = obj.read()
            try:
                t.set_image(src, target_format=TextureFormat.RGBA32, mipmap_count=1)
            except TypeError:
                t.image = src
            t.save()
            count += 1
        except Exception as e:
            print("  texture failed:", e)
    if count:
        try:
            out = env.file.save(packer="lz4")
        except TypeError:
            out = env.file.save()
        with open(path, "wb") as f:
            f.write(out)
        changed[n] = path
        total += count
        print(f"{n}: {count} textures replaced")

if not total:
    sys.exit("No Texture2D replaced - the game may not be Unity, or its textures are stored elsewhere.")
print("Total textures replaced:", total)

SIG = (".RSA", ".SF", ".MF", ".DSA", ".EC")
with zipfile.ZipFile(apk_out, "w") as zo:
    for info in zin.infolist():
        if info.filename.startswith("META-INF/") and info.filename.endswith(SIG):
            continue
        if info.filename in changed:
            with open(changed[info.filename], "rb") as f:
                data = f.read()
        else:
            data = zin.read(info)
        zi = zipfile.ZipInfo(info.filename, info.date_time)
        zi.compress_type = info.compress_type
        zo.writestr(zi, data)
print("Wrote", apk_out)
