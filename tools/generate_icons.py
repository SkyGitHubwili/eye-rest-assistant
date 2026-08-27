from pathlib import Path
from math import pi, cos, sin
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "assets" / "brand"
BRAND.mkdir(parents=True, exist_ok=True)

size = 1024
image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
pixels = image.load()

# Rounded diagonal forest-green gradient.
mask = Image.new("L", (size, size), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle((32, 32, 992, 992), radius=224, fill=255)
for y in range(size):
    for x in range(size):
        t = max(0.0, min(1.0, (x + y - 160) / 1728))
        start, end = (20, 61, 54), (74, 169, 119)
        pixels[x, y] = tuple(round(start[i] * (1 - t) + end[i] * t) for i in range(3)) + (mask.getpixel((x, y)),)

draw = ImageDraw.Draw(image, "RGBA")
draw.arc((112, 112, 492, 492), 190, 270, fill=(221, 242, 229, 62), width=28)
draw.arc((532, 532, 912, 912), 10, 90, fill=(243, 220, 169, 88), width=28)

# Soft eye shadow.
shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
sd = ImageDraw.Draw(shadow)
eye_points = []
for i in range(81):
    t = i / 80
    x = 212 + 600 * t
    y = (1-t)*(1-t)*512 + 2*(1-t)*t*214 + t*t*512
    eye_points.append((x, y + 22))
for i in range(80, -1, -1):
    t = i / 80
    x = 212 + 600 * t
    y = (1-t)*(1-t)*512 + 2*(1-t)*t*810 + t*t*512
    eye_points.append((x, y + 22))
sd.polygon(eye_points, fill=(8, 38, 31, 88))
shadow = shadow.filter(ImageFilter.GaussianBlur(28))
image.alpha_composite(shadow)

draw = ImageDraw.Draw(image, "RGBA")
eye_points = []
for i in range(81):
    t = i / 80
    eye_points.append((212 + 600*t, (1-t)*(1-t)*512 + 2*(1-t)*t*214 + t*t*512))
for i in range(80, -1, -1):
    t = i / 80
    eye_points.append((212 + 600*t, (1-t)*(1-t)*512 + 2*(1-t)*t*810 + t*t*512))
draw.polygon(eye_points, fill=(247, 250, 244, 255))
draw.ellipse((368, 368, 656, 656), fill=(114, 200, 155, 255))
draw.ellipse((430, 430, 594, 594), fill=(22, 72, 60, 255))
draw.ellipse((452, 448, 508, 504), fill=(247, 250, 244, 255))

# Two leaves connect eye care with nature/rest.
draw.polygon([(708,305),(753,302),(793,318),(830,369),(782,374),(740,355)], fill=(221,242,182,255))
draw.polygon([(698,320),(716,366),(704,410),(662,452),(648,406),(661,361)], fill=(185,229,147,255))

master = BRAND / "eye-rest-icon.png"
image.save(master, optimize=True)

ico_sizes = [(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)]
image.save(BRAND / "eye-rest.ico", format="ICO", sizes=ico_sizes)

android = ROOT / "mobile" / "android" / "res"
densities = {"mipmap-mdpi":48, "mipmap-hdpi":72, "mipmap-xhdpi":96, "mipmap-xxhdpi":144, "mipmap-xxxhdpi":192}
for folder, edge in densities.items():
    target = android / folder
    target.mkdir(parents=True, exist_ok=True)
    image.resize((edge, edge), Image.Resampling.LANCZOS).save(target / "ic_launcher.png", optimize=True)

print(master)
