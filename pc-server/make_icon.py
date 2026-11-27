from PIL import Image, ImageDraw

size = 256
img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# Dark navy rounded square background
draw.rounded_rectangle([8, 8, 248, 248], radius=56, fill="#0D1326")

# Mouse coordinates
mx1, my1, mx2, my2 = 72, 38, 184, 218
mid_x = 128
split_y = 112

# Full white rounded mouse body
draw.rounded_rectangle([mx1, my1, mx2, my2], radius=54, fill="#FFFFFF")

# Top left blue button
draw.pieslice([mx1, my1, mx2, my1 + 108], start=180, end=270, fill="#2563EB")
draw.rectangle([mx1, my1 + 54, mid_x - 2, split_y], fill="#2563EB")

# Top right purple button
draw.pieslice([mx1, my1, mx2, my1 + 108], start=270, end=360, fill="#7C3AED")
draw.rectangle([mid_x + 2, my1 + 54, mx2, split_y], fill="#7C3AED")

# Divider lines
draw.line([mx1, split_y, mx2, split_y], fill="#0D1326", width=4)
draw.line([mid_x, my1, mid_x, split_y], fill="#0D1326", width=4)

# Scroll wheel in middle
draw.rounded_rectangle([mid_x - 7, my1 + 22, mid_x + 7, my1 + 56], radius=7, fill="#0D1326")

img.save("app_icon.ico", format="ICO", sizes=[(256, 256), (128, 128), (64, 64), (32, 32)])
img.save("app_icon.png", format="PNG")
print("Icon created successfully!")