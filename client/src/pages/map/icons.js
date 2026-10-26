import L from 'leaflet';

const esc = (s) => String(s).replace(/[&<>"]/g, '');

export const storeIcon = (initials, moving) => L.divIcon({
  className: 'mk-wrap', iconSize: [34, 34], iconAnchor: [17, 17], popupAnchor: [0, -18], tooltipAnchor: [18, 0],
  html: `<span class="mk-store${moving ? ' moving' : ''}">${esc(initials)}</span>`
});

export const agentIcon = (initials, status) => L.divIcon({
  className: 'mk-wrap', iconSize: [40, 40], iconAnchor: [20, 20], popupAnchor: [0, -20], tooltipAnchor: [20, 0],
  html: `<span class="mk-agent ${status}">${status === 'tracking' ? '<i class="pulse"></i>' : ''}<b>${esc(initials)}</b></span>`
});

export const stopIcon = (n, size, color, amber) => L.divIcon({
  className: 'mk-wrap', iconSize: [size, size], iconAnchor: [size / 2, size / 2], popupAnchor: [0, -size / 2],
  html: `<span class="mk-stop${amber ? ' amber' : ''}" style="width:${size}px;height:${size}px;border-color:${color || 'transparent'}">${esc(n)}</span>`
});

export const letterIcon = (letter, cls) => L.divIcon({
  className: 'mk-wrap', iconSize: [26, 26], iconAnchor: [13, 13],
  html: `<span class="mk-pin ${cls}">${esc(letter)}</span>`
});

export const dotIcon = () => L.divIcon({
  className: 'mk-wrap', iconSize: [20, 20], iconAnchor: [10, 10],
  html: '<span class="mk-play"></span>'
});
