#!/usr/bin/env bash
# Downloads the Philippines OSM extract, cuts it to the Negros + Siquijor bbox, and preprocesses it (car, MLD).
# Re-run monthly to refresh roads (see cron line in CLAUDE.md). Widen BBOX to cover more islands;
# the whole Philippines needs ~6–8 GB RAM to preprocess.
set -euo pipefail
BBOX="122.35,8.95,123.80,11.05"   # minLon,minLat,maxLon,maxLat — Negros island (Dumaguete) + Siquijor
D=/opt/osrm/data
mkdir -p $D && cd $D
curl -sSL -z philippines-latest.osm.pbf -o philippines-latest.osm.pbf https://download.geofabrik.de/asia/philippines-latest.osm.pbf
osmium extract -b "$BBOX" philippines-latest.osm.pbf -o region.new.osm.pbf --overwrite
/opt/osrm/bin/osrm-extract -p /opt/osrm/share/osrm/profiles/car.lua region.new.osm.pbf
/opt/osrm/bin/osrm-partition region.new.osrm
/opt/osrm/bin/osrm-customize region.new.osrm
for f in region.new.osrm*; do mv "$f" "${f/region.new/region}"; done
mv region.new.osm.pbf region.osm.pbf
systemctl restart osrm-routed 2>/dev/null || true
echo "map built: $(du -sh $D | cut -f1)"
