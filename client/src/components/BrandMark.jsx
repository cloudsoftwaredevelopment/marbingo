// The official Marbingo logo in a rounded white tile. Used in the sidebar, login and the mobile top bar.
// The logo art (public/logo.png) has a white interior, so a white tile reads as one solid mark on any background.
export default function BrandMark({ size = 40, radius = 10, className }) {
  return (
    <span
      aria-hidden="true"
      className={className}
      style={{
        width: size, height: size, flex: `0 0 ${size}px`, borderRadius: radius,
        background: '#FFFFFF', display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        overflow: 'hidden', boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.06)',
      }}
    >
      <img src="/logo.png" alt="" width={Math.round(size * 0.82)} height={Math.round(size * 0.82)}
        style={{ objectFit: 'contain', display: 'block' }} />
    </span>
  );
}
