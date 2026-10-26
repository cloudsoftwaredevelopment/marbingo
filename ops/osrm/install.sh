#!/usr/bin/env bash
# One-time: build OSRM v5.27.1 from source into /opt/osrm, build the Negros + Siquijor map, install the systemd service.
# Run as root:  bash /var/www/marbingo/ops/osrm/install.sh      (~30–60 min, mostly compiling)
set -euo pipefail
VER=5.27.1
SRC=/opt/osrm/src/osrm-backend-$VER

DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential git cmake pkg-config libbz2-dev \
  libxml2-dev libzip-dev libboost-all-dev lua5.4 liblua5.4-dev libtbb-dev osmium-tool

mkdir -p /opt/osrm/src /opt/osrm/data
if [ ! -d "$SRC" ]; then
  curl -sSL -o /opt/osrm/src/osrm-$VER.tar.gz https://github.com/Project-OSRM/osrm-backend/archive/refs/tags/v$VER.tar.gz
  tar xzf /opt/osrm/src/osrm-$VER.tar.gz -C /opt/osrm/src
fi

if [ ! -x /opt/osrm/bin/osrm-routed ]; then
  mkdir -p "$SRC/build" && cd "$SRC/build"
  # GCC 13 / Boost 1.83 need relaxed warnings and an explicit <cstdint> for this release.
  cmake .. -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/osrm -DBUILD_TESTING=OFF \
    -DCMAKE_CXX_FLAGS="-Wno-error -Wno-array-bounds -Wno-stringop-overflow -Wno-uninitialized -Wno-deprecated-declarations -include cstdint"
  make -j3
  make install
fi

bash /var/www/marbingo/ops/osrm/build-map.sh

install -m 644 /var/www/marbingo/ops/osrm/osrm-routed.service /etc/systemd/system/osrm-routed.service
systemctl daemon-reload
systemctl enable --now osrm-routed
sleep 3
curl -s "http://127.0.0.1:5010/route/v1/driving/123.3054,9.3068;123.2965,9.3175?overview=false" | head -c 200; echo
echo "OSRM ready. Add OSRM_URL=http://127.0.0.1:5010 to /var/www/marbingo/server/.env and pm2 restart marbingo."
