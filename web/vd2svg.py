import sys,re,json,glob,os,xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
def col(c):
    if not c or c.startswith('@') or c.startswith('?'): return 'currentColor'
    c=c.lstrip('#')
    if len(c)==8:
        a=int(c[:2],16)/255; return f'rgba({int(c[2:4],16)},{int(c[4:6],16)},{int(c[6:],16)},{a:.3g})'
    if len(c)==6: return '#'+c
    if len(c)==3: return '#'+c
    return 'currentColor'
def conv(p):
    r=ET.parse(p).getroot()
    vw=r.get(A+'viewportWidth'); vh=r.get(A+'viewportHeight')
    out=[]
    def walk(e,indent=''):
        for ch in e:
            tag=ch.tag
            if tag=='group':
                t=[]
                tx=ch.get(A+'translateX') or '0'; ty=ch.get(A+'translateY') or '0'
                if tx!='0' or ty!='0': t.append(f'translate({tx},{ty})')
                px=ch.get(A+'pivotX') or '0'; py=ch.get(A+'pivotY') or '0'
                rot=ch.get(A+'rotation')
                if rot: t.append(f"rotate({rot},{px},{py})")
                sx=ch.get(A+'scaleX'); sy=ch.get(A+'scaleY')
                if sx or sy: t.append(f'translate({px},{py}) scale({sx or 1},{sy or 1}) translate(-{px},-{py})'.replace('--',''))
                out.append(f'<g transform="{" ".join(t)}">' if t else '<g>'); walk(ch); out.append('</g>')
            elif tag=='path':
                d=ch.get(A+'pathData'); fc=ch.get(A+'fillColor'); sc=ch.get(A+'strokeColor')
                at=[f'd="{d}"', f'fill="{col(fc) if fc else "none"}"']
                if fc and len(fc.lstrip('#'))==8 and fc.lstrip('#')[:2]=='00': at[1]='fill="none"'
                if sc: at+= [f'stroke="{col(sc)}"', f'stroke-width="{ch.get(A+"strokeWidth") or 1}"']
                lc=ch.get(A+'strokeLineCap'); lj=ch.get(A+'strokeLineJoin')
                if lc: at.append(f'stroke-linecap="{lc}"')
                if lj: at.append(f'stroke-linejoin="{lj}"')
                ty=ch.get(A+'translateY')
                if ty: at.append(f'transform="translate(0,{ty})"')
                if ch.get(A+'fillType')=='evenOdd': at.append('fill-rule="evenodd"')
                out.append('<path '+' '.join(at)+'/>')
    walk(r)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}">'+''.join(out)+'</svg>'
d=sys.argv[1]; icons={}
for p in sorted(glob.glob(d+'/turn_*.xml')+[d+'/'+n+'.xml' for n in sys.argv[3].split(',')]):
    n=os.path.basename(p)[:-4]
    if n in('turn_icon_bg',): continue
    try: icons[n]=conv(p)
    except Exception as e: print('skip',n,e,file=sys.stderr)
open(sys.argv[2],'w').write('// Converted from the Android app\'s vector drawables (res/drawable).\nexport const ICONS = '+json.dumps(icons,indent=0)+';\n')
print(len(icons), list(icons))
