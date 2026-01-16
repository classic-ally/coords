#!/bin/bash
# Generate licenses.json for embedding in the binary
# Run this before release builds

set -e
cd "$(dirname "$0")"

echo "Generating license information..."

# Generate full license info
cargo about generate --format json > licenses-full.json

# Process into grouped format with full license texts
python3 << 'PYTHON'
import json

with open('licenses-full.json') as f:
    data = json.load(f)

# Build a map of license id -> {name, text, packages}
license_map = {}

# First, get license texts from the overview
for lic in data.get('overview', []):
    lic_id = lic.get('id', '')
    lic_name = lic.get('name', '')
    lic_text = lic.get('text', '').strip()

    if lic_id and lic_id not in license_map:
        license_map[lic_id] = {
            'id': lic_id,
            'name': lic_name,
            'text': lic_text,
            'packages': []
        }

# Now assign packages to licenses, normalizing dual-licensed to Apache-2.0
seen = set()
for crate in data.get('crates', []):
    pkg = crate.get('package', {})
    name = pkg.get('name', '')
    version = pkg.get('version', '')
    license_expr = crate.get('license', '')

    # Skip if we've seen this crate
    key = f"{name}-{version}"
    if key in seen:
        continue
    seen.add(key)

    # Normalize dual licenses to Apache-2.0
    if 'Apache-2.0' in license_expr and ('MIT' in license_expr or 'OR' in license_expr):
        lic_id = 'Apache-2.0'
    else:
        # Try to find exact match or first part
        lic_id = license_expr.split(' ')[0] if license_expr else 'Unknown'

    if lic_id in license_map:
        license_map[lic_id]['packages'].append(f"{name} {version}")

# Convert to list and sort
licenses = list(license_map.values())
licenses.sort(key=lambda x: x['name'].lower())

# Sort packages within each license
for lic in licenses:
    lic['packages'].sort(key=str.lower)

# Filter out licenses with no packages
licenses = [l for l in licenses if l['packages']]

# Write as JSON for embedding
with open('licenses.json', 'w') as f:
    json.dump(licenses, f, separators=(',', ':'))

total_packages = sum(len(l['packages']) for l in licenses)
print(f"Generated licenses.json with {len(licenses)} licenses covering {total_packages} packages")
for lic in licenses:
    print(f"  {lic['name']}: {len(lic['packages'])} packages")
PYTHON

# Clean up
rm -f licenses-full.json

echo "Done! licenses.json ready for embedding"
