#!/usr/bin/env python3
"""
Preprocess cities.json and boundaries.json for the Coords app.

This script:
1. Updates city region names based on Natural Earth polygon containment
2. Generates a pruned boundaries.json containing only regions with cities
"""

import json
import sys
from pathlib import Path
from shapely.geometry import shape, mapping, Point
from shapely.strtree import STRtree
import argparse

# Region name normalizations to ensure consistency with polygon data
REGION_NORMALIZATIONS = {
    "Quebec": "Québec",
    "Lisbon": "Lisboa",
    "South Denmark": "Syddanmark",
    "Capital Region": "Hovedstaden",  # Denmark
}


def normalize_region(name: str) -> str:
    """Normalize region names to match polygon data."""
    return REGION_NORMALIZATIONS.get(name, name)


def load_boundaries(geojson_path: Path) -> tuple[list, list, list, STRtree]:
    """Load boundary polygons and build spatial index."""
    with open(geojson_path) as f:
        data = json.load(f)

    features = data["features"]
    geometries = []
    properties = []

    for feature in features:
        geom = shape(feature["geometry"])
        geometries.append(geom)
        properties.append(feature["properties"])

    # Build spatial index for fast lookups
    tree = STRtree(geometries)
    return features, geometries, properties, tree


def find_region(lat: float, lng: float, geometries: list, properties: list, tree: STRtree) -> dict | None:
    """Find which region polygon contains the given point."""
    point = Point(lng, lat)  # Note: Point takes (x, y) = (lng, lat)

    # Query spatial index for candidates
    candidates = tree.query(point)

    for idx in candidates:
        geom = geometries[idx]
        if geom.contains(point):
            return properties[idx]

    return None


def process_cities(
    cities_path: Path,
    boundaries_path: Path,
    output_cities_path: Path,
    output_boundaries_path: Path,
):
    """Process cities.json and boundaries, output updated files."""

    print(f"Loading boundaries from {boundaries_path}...")
    features, geometries, properties, tree = load_boundaries(boundaries_path)
    print(f"  Loaded {len(geometries)} regions")

    print(f"Loading cities from {cities_path}...")
    with open(cities_path) as f:
        cities = json.load(f)
    print(f"  Loaded {len(cities)} cities")

    # Process each city
    matched = 0
    unmatched = 0
    updated = 0
    regions_with_cities = set()  # Track (region, country) pairs that have cities

    for i, city in enumerate(cities):
        if i % 5000 == 0:
            print(f"  Processing city {i}/{len(cities)}...")

        lat = city.get("la")
        lng = city.get("lo")

        if lat is None or lng is None:
            continue

        region_props = find_region(lat, lng, geometries, properties, tree)

        if region_props:
            matched += 1
            old_region = city.get("r", "")
            new_region = region_props.get("name", "")
            new_country = region_props.get("admin", "")

            # Track if we're changing anything
            if old_region != new_region:
                updated += 1

            # Update city with polygon-derived data
            city["r"] = new_region
            # Optionally update country too if it differs
            if new_country and city.get("c") != new_country:
                city["c"] = new_country

            # Track this region as having cities
            regions_with_cities.add((new_region, new_country))
        else:
            unmatched += 1

    # Normalize region names for unmatched cities (e.g., "Quebec" -> "Québec")
    normalized = 0
    for city in cities:
        old_region = city.get("r", "")
        new_region = normalize_region(old_region)
        if old_region != new_region:
            city["r"] = new_region
            normalized += 1
            # Also track normalized regions
            country = city.get("c", "")
            if country:
                regions_with_cities.add((new_region, country))

    print(f"\nCity results:")
    print(f"  Matched: {matched}")
    print(f"  Unmatched: {unmatched}")
    print(f"  Regions updated: {updated}")
    print(f"  Regions normalized: {normalized}")

    # Write cities output
    print(f"\nWriting cities to {output_cities_path}...")
    with open(output_cities_path, "w") as f:
        json.dump(cities, f, separators=(",", ":"))

    # Prune boundaries to only regions with cities
    print(f"\nPruning boundaries...")
    pruned_features = []
    for feature in features:
        props = feature["properties"]
        name = props.get("name", "")
        admin = props.get("admin", "")
        if (name, admin) in regions_with_cities:
            pruned_features.append(feature)

    print(f"  Regions with cities: {len(pruned_features)} / {len(features)}")

    # Write pruned boundaries
    print(f"Writing boundaries to {output_boundaries_path}...")
    pruned_geojson = {"type": "FeatureCollection", "features": pruned_features}
    with open(output_boundaries_path, "w") as f:
        json.dump(pruned_geojson, f, separators=(",", ":"))

    import os
    size_mb = os.path.getsize(output_boundaries_path) / 1024 / 1024
    print(f"  Size: {size_mb:.2f}MB")

    print("\nDone!")


def main():
    parser = argparse.ArgumentParser(description="Preprocess cities and boundaries for Coords")
    parser.add_argument("--cities", type=Path, required=True, help="Input cities.json")
    parser.add_argument("--boundaries", type=Path, required=True, help="Input boundaries GeoJSON")
    parser.add_argument("--output-cities", type=Path, required=True, help="Output cities.json")
    parser.add_argument("--output-boundaries", type=Path, required=True, help="Output boundaries.json")

    args = parser.parse_args()

    if not args.cities.exists():
        print(f"Error: cities file not found: {args.cities}", file=sys.stderr)
        sys.exit(1)

    if not args.boundaries.exists():
        print(f"Error: boundaries file not found: {args.boundaries}", file=sys.stderr)
        sys.exit(1)

    process_cities(args.cities, args.boundaries, args.output_cities, args.output_boundaries)


if __name__ == "__main__":
    main()
