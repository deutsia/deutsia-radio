#!/usr/bin/env python3
"""
Convert Android Vector Drawable to PNG for Play Store
"""
import cairosvg
import xml.etree.ElementTree as ET

# Read the Android Vector Drawable
tree = ET.parse('app/src/main/res/drawable/ic_launcher_foreground.xml')
root = tree.getroot()

# Extract viewBox dimensions
width = root.get('{http://schemas.android.com/apk/res/android}width', '108dp').replace('dp', '')
height = root.get('{http://schemas.android.com/apk/res/android}height', '108dp').replace('dp', '')
viewportWidth = root.get('{http://schemas.android.com/apk/res/android}viewportWidth', '108')
viewportHeight = root.get('{http://schemas.android.com/apk/res/android}viewportHeight', '108')

# Start building SVG
svg_parts = [
    f'<?xml version="1.0" encoding="UTF-8"?>',
    f'<svg xmlns="http://www.w3.org/2000/svg" ',
    f'width="{width}" height="{height}" ',
    f'viewBox="0 0 {viewportWidth} {viewportHeight}">'
]

# Convert path elements
for path_elem in root.findall('.//{http://schemas.android.com/apk/res/android}path'):
    path_data = path_elem.get('{http://schemas.android.com/apk/res/android}pathData', '')
    fill_color = path_elem.get('{http://schemas.android.com/apk/res/android}fillColor', 'none')
    stroke_color = path_elem.get('{http://schemas.android.com/apk/res/android}strokeColor', 'none')
    stroke_width = path_elem.get('{http://schemas.android.com/apk/res/android}strokeWidth', '0')
    stroke_linecap = path_elem.get('{http://schemas.android.com/apk/res/android}strokeLineCap', 'butt')

    svg_parts.append(f'  <path d="{path_data}" ')
    svg_parts.append(f'fill="{fill_color}" ')
    if stroke_color != 'none' and stroke_color != '#00000000':
        svg_parts.append(f'stroke="{stroke_color}" ')
        svg_parts.append(f'stroke-width="{stroke_width}" ')
        svg_parts.append(f'stroke-linecap="{stroke_linecap}" ')
    svg_parts.append('/>')

svg_parts.append('</svg>')

svg_content = '\n'.join(svg_parts)

# Save as SVG
with open('icon_temp.svg', 'w') as f:
    f.write(svg_content)

print("Converting to 512x512 PNG...")

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

import os
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
