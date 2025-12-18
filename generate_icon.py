#!/usr/bin/env python3
"""
Convert Android Vector Drawable to PNG for Play Store
"""
import cairosvg
import xml.etree.ElementTree as ET
import os

# Read the Android Vector Drawable
tree = ET.parse('app/src/main/res/drawable/ic_launcher_foreground.xml')
root = tree.getroot()

# Android namespace
android_ns = '{http://schemas.android.com/apk/res/android}'

# Extract viewBox dimensions
viewportWidth = root.get(f'{android_ns}viewportWidth', '108')
viewportHeight = root.get(f'{android_ns}viewportHeight', '108')

# Start building SVG
svg_content = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     width="512" height="512"
     viewBox="0 0 {viewportWidth} {viewportHeight}">
'''

# Debug: Print what we find
print(f"Root tag: {root.tag}")
print(f"Root attribs: {root.attrib}")

# Convert path elements - try different ways to find them
paths_found = 0
for path_elem in root.iter():
    if path_elem.tag.endswith('path'):
        paths_found += 1
        path_data = path_elem.get(f'{android_ns}pathData', '')
        fill_color = path_elem.get(f'{android_ns}fillColor', 'none')
        stroke_color = path_elem.get(f'{android_ns}strokeColor', 'none')
        stroke_width = path_elem.get(f'{android_ns}strokeWidth', '0')
        stroke_linecap = path_elem.get(f'{android_ns}strokeLineCap', 'butt')

        if not path_data:
            print(f"Warning: path element {paths_found} has no pathData")
            continue

        # Convert fill color
        if fill_color == '#00000000':
            fill_color = 'none'

        svg_content += f'  <path d="{path_data}" '
        svg_content += f'fill="{fill_color}" '

        if stroke_color != 'none' and stroke_color != '#00000000':
            svg_content += f'stroke="{stroke_color}" '
            svg_content += f'stroke-width="{stroke_width}" '
            svg_content += f'stroke-linecap="{stroke_linecap}" '

        svg_content += '/>\n'

print(f"Found {paths_found} path elements")

svg_content += '</svg>'

# Save as SVG for debugging
with open('icon_temp.svg', 'w') as f:
    f.write(svg_content)

print("SVG saved to icon_temp.svg")
print("\nConverting to 512x512 PNG...")

# Convert to 512x512 PNG
cairosvg.svg2png(
    bytestring=svg_content.encode('utf-8'),
    write_to='assets/playstore-icon-512.png',
    output_width=512,
    output_height=512
)

print("✓ Generated: assets/playstore-icon-512.png (512x512)")

# Also generate different sizes for mipmap folders
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

for density, size in sizes.items():
    # Create directory if it doesn't exist
    os.makedirs(f'app/src/main/res/mipmap-{density}', exist_ok=True)

    output_path = f'app/src/main/res/mipmap-{density}/ic_launcher.png'
    cairosvg.svg2png(
        bytestring=svg_content.encode('utf-8'),
        write_to=output_path,
        output_width=size,
        output_height=size
    )
    print(f"✓ Generated: {output_path} ({size}x{size})")

    # Also create round version
    output_path_round = f'app/src/main/res/mipmap-{density}/ic_launcher_round.png'
    cairosvg.svg2png(
        bytestring=svg_content.encode('utf-8'),
        write_to=output_path_round,
        output_width=size,
        output_height=size
    )
    print(f"✓ Generated: {output_path_round} ({size}x{size})")

print("\n✓ All icons generated successfully!")
print("\nFor Play Store: Use assets/playstore-icon-512.png")
print("For Android 7.x fallback: PNG files added to mipmap folders")
