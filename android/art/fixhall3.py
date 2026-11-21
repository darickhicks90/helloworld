from PIL import Image
im=Image.open('hall_blossom_fixed2.png').convert('RGBA'); px=im.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
def headtop(ox):
    for y in range(72):
        xs=[x for x in range(ox,ox+42) if px[x,y][3]>200]
        if xs: return (sum(xs)//len(xs)-ox, y)
h0=headtop(0)
for f in range(6):
    ox=f*72
    hx,hy=headtop(ox); dx=hx-h0[0]; dy=hy-h0[1]
    cell=[(x,y) for x in range(ox,ox+72) for y in range(72) if px[x,y][3]>200]
    furs=sorted([px[x,y] for (x,y) in cell if lum(px[x,y])>150 and abs(px[x,y][0]-px[x,y][2])<60], key=lum)
    LIGHT=furs[int(len(furs)*0.85)]; MIDC=furs[int(len(furs)*0.45)]; DARKC=furs[int(len(furs)*0.1)]
    OUT=min((px[x,y] for (x,y) in cell), key=lum)
    X=lambda x: ox+x+dx; Y=lambda y: y+dy
    # the raised arm (and its glow, on the casting frame), gone
    for y in range(0,Y(33)):
        for x in range(X(44) if y>=Y(22) else X(42),ox+72): px[x,y]=(0,0,0,0)
    for y in range(0,Y(14)):
        for x in range(X(36),ox+72):
            c=px[x,y]
            if c[3]<255 or (c[1]>c[0] and c[1]>c[2]): px[x,y]=(0,0,0,0)
    # her shoulder's outline
    for y in range(Y(18),Y(34)):
        xs=[x for x in range(ox,ox+72) if px[x,y][3]>200]
        if xs and lum(px[max(xs),y])>90: px[max(xs)+1,y]=OUT
    def put(x,y,c): px[X(x),Y(y)]=c
    # upper arm down her side to the elbow
    for y in range(26,39):
        put(43,y,LIGHT); put(44,y,MIDC); put(45,y,DARKC); put(46,y,OUT)
    put(43,39,MIDC); put(44,39,MIDC); put(45,39,OUT); put(43,40,OUT); put(44,40,OUT)
    # the forearm, up and across in front of her chest
    for k,(x,y) in enumerate([(42,38),(41,37),(40,36),(39,35),(38,34)]):
        put(x,y,LIGHT); put(x,y+1,MIDC); put(x,y+2,OUT)
    # the open hand at her chest, fingers up at an angle
    for (x,y) in [(35,31),(36,31),(37,31),(35,32),(36,32),(37,32),(36,33),(37,33)]: put(x,y,LIGHT)
    for (x,y) in [(34,30),(36,29),(38,30)]: put(x,y,LIGHT); put(x,y-1,OUT)
    for (x,y) in [(34,31),(34,32),(38,31),(38,32),(35,33),(35,30),(37,30)]: put(x,y,OUT if (x,y) in [(34,32),(38,32),(35,33)] else MIDC)
im.save('hall_blossom_fixed3.png')
c=im.crop((0,0,72*6,72)); bg=Image.new('RGBA',c.size,(120,170,90,255)); bg.alpha_composite(c); bg.resize((c.width*4,c.height*4),Image.NEAREST).save('ph_fixed3.png')
print('ok')
