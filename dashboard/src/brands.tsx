import { CSSProperties } from 'react'

/**
 * Display-ad creatives for the demo advertisers. They are original designs in each brand's general colour world, built from simple shapes and
 * the brand name as plain text. No logos, product photography or real slogans are reproduced.
 */
export interface Brand {
  key: string
  bg: string
  accent: string
  ctaFg: string
  dot: string
  cta: string
  line: string
  motif: 'stripes' | 'arcs' | 'bars' | 'plate' | 'card' | 'road' | 'pin' | 'drop'
}

const BRANDS: Record<string, Brand> = {
  Nike: { key: 'Nike', bg: 'linear-gradient(135deg,#0c0c0c,#202020)', accent: '#ff5a1f', ctaFg: '#ffffff', dot: '#ff5a1f', cta: 'Shop now', line: 'New arrivals and member offers', motif: 'stripes' },
  Samsung: { key: 'Samsung', bg: 'linear-gradient(135deg,#071a47,#1647b8)', accent: '#8fd8ff', ctaFg: '#06214f', dot: '#1647b8', cta: 'Pre-order', line: 'Latest devices and festive deals', motif: 'arcs' },
  Spotify: { key: 'Spotify', bg: 'linear-gradient(135deg,#07120b,#12351f)', accent: '#1ed760', ctaFg: '#06130a', dot: '#1ed760', cta: 'Try free', line: 'Music and podcasts, your way', motif: 'bars' },
  Zomato: { key: 'Zomato', bg: 'linear-gradient(135deg,#d92b3a,#f6545f)', accent: '#fff6ea', ctaFg: '#b01f2d', dot: '#e23744', cta: 'Order now', line: 'Delivery and dining offers', motif: 'plate' },
  'HDFC Bank': { key: 'HDFC Bank', bg: 'linear-gradient(135deg,#072f63,#0f5bb0)', accent: '#ff4d4d', ctaFg: '#ffffff', dot: '#0f5bb0', cta: 'Apply now', line: 'Cards, loans and savings', motif: 'card' },
  Hyundai: { key: 'Hyundai', bg: 'linear-gradient(135deg,#0a1630,#1d3f7a)', accent: '#7db8ff', ctaFg: '#08193a', dot: '#2c5aa8', cta: 'Book a test drive', line: 'Models, offers and test drives', motif: 'road' },
  'Booking.com': { key: 'Booking.com', bg: 'linear-gradient(135deg,#002a6b,#0050c8)', accent: '#ffc21a', ctaFg: '#1a1400', dot: '#0050c8', cta: 'Find stays', line: 'Hotels, flights and getaways', motif: 'pin' },
  Nykaa: { key: 'Nykaa', bg: 'linear-gradient(135deg,#e5106a,#ff6aa5)', accent: '#ffffff', ctaFg: '#c20a58', dot: '#fc2779', cta: 'Shop beauty', line: 'Beauty, skincare and fashion', motif: 'drop' },
}

const FALLBACK: Brand = { key: 'Advertiser', bg: 'linear-gradient(135deg,#2a2a2a,#4a4a4a)', accent: '#f2f2f2', ctaFg: '#111111', dot: '#777777', cta: 'Learn more', line: 'Sponsored', motif: 'arcs' }

/** "Nike | Air Max Day Launch" -> { brand, title } */
export function brandOf(campaignName: string): { brand: Brand; title: string } {
  const [b, ...rest] = campaignName.split(' | ')
  const title = (rest.join(' | ') || campaignName).replace('Brand Awareness Always-On', 'Brand spotlight')
  return { brand: BRANDS[b] ?? { ...FALLBACK, key: b }, title }
}

function Motif({ m, accent }: { m: Brand['motif']; accent: string }) {
  const a = accent
  switch (m) {
    case 'stripes':
      return (<g fill={a}><polygon points="20,170 120,40 152,40 52,170" opacity=".95" /><polygon points="72,170 172,40 192,40 92,170" opacity=".55" /><polygon points="124,170 204,66 218,66 144,170" opacity=".3" /></g>)
    case 'arcs':
      return (<g fill="none" stroke={a} strokeWidth="2.4"><circle cx="138" cy="104" r="40" opacity=".9" /><circle cx="138" cy="104" r="64" opacity=".5" /><circle cx="138" cy="104" r="88" opacity=".25" /><rect x="116" y="62" width="44" height="84" rx="10" fill={a} fillOpacity=".16" /></g>)
    case 'bars':
      return (<g fill={a}>{[52, 96, 138, 76, 118, 160, 64].map((h, i) => <rect key={i} x={26 + i * 24} y={190 - h} width="14" height={h} rx="7" opacity={0.35 + (i % 3) * 0.3} />)}</g>)
    case 'plate':
      return (<g fill={a}><circle cx="140" cy="108" r="76" opacity=".16" /><circle cx="140" cy="108" r="52" opacity=".22" /><circle cx="140" cy="108" r="22" opacity=".5" /><rect x="40" y="52" width="6" height="100" rx="3" opacity=".6" /><rect x="54" y="52" width="6" height="100" rx="3" opacity=".6" /><rect x="68" y="52" width="6" height="100" rx="3" opacity=".6" /></g>)
    case 'card':
      return (<g><rect x="26" y="58" width="156" height="98" rx="14" fill="#fff" fillOpacity=".12" stroke="#fff" strokeOpacity=".5" strokeWidth="2" /><rect x="44" y="82" width="30" height="22" rx="5" fill={a} /><rect x="44" y="124" width="92" height="7" rx="3.5" fill="#fff" fillOpacity=".55" /><circle cx="152" cy="132" r="14" fill={a} fillOpacity=".9" /><circle cx="166" cy="132" r="14" fill="#fff" fillOpacity=".5" /></g>)
    case 'road':
      return (<g><polygon points="94,66 110,66 196,200 8,200" fill={a} fillOpacity=".2" /><g stroke={a} strokeWidth="3" strokeDasharray="10 12" opacity=".8"><line x1="102" y1="72" x2="102" y2="200" /></g><circle cx="150" cy="44" r="26" fill={a} opacity=".55" /></g>)
    case 'pin':
      return (<g><path d="M140 34 a40 40 0 0 1 40 40 c0 34 -40 78 -40 78 s-40 -44 -40 -78 a40 40 0 0 1 40 -40z" fill={a} /><circle cx="140" cy="74" r="15" fill="#003580" /><path d="M20 176 q22 -14 44 0 t44 0 t44 0 t44 0" fill="none" stroke={a} strokeWidth="3" opacity=".6" /><path d="M20 192 q22 -14 44 0 t44 0 t44 0 t44 0" fill="none" stroke={a} strokeWidth="3" opacity=".3" /></g>)
    case 'drop':
      return (<g fill={a}><circle cx="150" cy="78" r="62" opacity=".16" /><circle cx="104" cy="152" r="44" opacity=".13" /><path d="M148 38 C172 76 186 94 186 118 a38 38 0 0 1 -76 0 C110 94 124 76 148 38z" opacity=".92" /></g>)
  }
}

/** Renders one display ad at any slot size. Everything scales with the slot's width (container query units), so it is crisp at any scale. */
export function AdCreative({ campaignName, w, h, style }: { campaignName: string; w: number; h: number; style?: CSSProperties }) {
  const { brand, title } = brandOf(campaignName)
  const ratio = w / h
  const wide = ratio >= 3.5
  const tall = ratio < 0.5
  const u = (h / w) * 100                       // the slot's height expressed in container-width units
  const root: CSSProperties = { aspectRatio: `${w} / ${h}`, containerType: 'inline-size', background: brand.bg, color: '#fff', position: 'relative', overflow: 'hidden', borderRadius: 6, ...style }
  const cta: CSSProperties = { background: brand.accent, color: brand.ctaFg, fontWeight: 700, borderRadius: 999, whiteSpace: 'nowrap', display: 'inline-block' }

  if (wide) {
    return (
      <div style={root}>
        <svg viewBox="0 0 200 200" preserveAspectRatio="xMaxYMid slice" style={{ position: 'absolute', right: 0, top: 0, height: '100%', width: '34%', opacity: 0.9 }}><Motif m={brand.motif} accent={brand.accent} /></svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', gap: `${u * 0.22}cqw`, padding: `0 ${u * 0.28}cqw` }}>
          <span className="font-display" style={{ fontSize: `${u * 0.34}cqw`, fontWeight: 700, letterSpacing: '-0.02em' }}>{brand.key}</span>
          <span style={{ width: 1, alignSelf: 'stretch', margin: `${u * 0.2}cqw 0`, background: 'rgba(255,255,255,.28)' }} />
          <span style={{ fontSize: `${u * 0.27}cqw`, fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</span>
          <span style={{ ...cta, fontSize: `${u * 0.2}cqw`, padding: `${u * 0.12}cqw ${u * 0.3}cqw`, marginRight: `${u * 0.1}cqw`, position: 'relative' }}>{brand.cta}</span>
        </div>
        <span style={{ position: 'absolute', left: `${u * 0.12}cqw`, bottom: `${u * 0.06}cqw`, fontSize: `${u * 0.11}cqw`, opacity: 0.55, letterSpacing: '.08em' }}>AD</span>
      </div>
    )
  }

  const k = tall ? 1.35 : 1                     // narrow slots need relatively larger type
  return (
    <div style={root}>
      <svg viewBox="0 0 200 200" preserveAspectRatio={tall ? 'xMidYMax meet' : 'xMaxYMid slice'}
        style={{ position: 'absolute', right: 0, bottom: 0, width: tall ? '100%' : '62%', height: tall ? '46%' : '100%', opacity: 0.95 }}>
        <Motif m={brand.motif} accent={brand.accent} />
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', padding: `${tall ? 9 : 7}cqw`, gap: `${2.2 * k}cqw` }}>
        <span className="font-display" style={{ fontSize: `${6.4 * k}cqw`, fontWeight: 700, letterSpacing: '-0.02em', opacity: 0.95 }}>{brand.key}</span>
        <span className="font-display" style={{ fontSize: `${(tall ? 12.5 : 10.4)}cqw`, fontWeight: 700, lineHeight: 1.08, letterSpacing: '-0.025em', maxWidth: tall ? '100%' : '66%' }}>{title}</span>
        <span style={{ fontSize: `${3.9 * k}cqw`, opacity: 0.82, lineHeight: 1.3, maxWidth: tall ? '100%' : '58%' }}>{brand.line}</span>
        <span style={{ ...cta, fontSize: `${4.2 * k}cqw`, padding: `${2.1 * k}cqw ${4.6 * k}cqw`, marginTop: tall ? '1.5cqw' : 'auto', alignSelf: 'flex-start', position: 'relative' }}>{brand.cta}</span>
      </div>
      <span style={{ position: 'absolute', right: `${3}cqw`, top: `${2.4}cqw`, fontSize: `${2.7 * k}cqw`, opacity: 0.55, letterSpacing: '.08em' }}>AD</span>
    </div>
  )
}
