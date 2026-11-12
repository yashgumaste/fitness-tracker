// Launcher icons in every accent colour. The app switches between them (see AppIconPlugin.java) so the
// home-screen icon matches the accent picked in Settings. Each icon is an adaptive icon: black background,
// accent "cookie" shape with a dark dumbbell, plus a monochrome layer for Android's themed icons.
const fs=require('fs'),path=require('path');

const ACCENTS=[['cyan','#00FFFF'],['teal','#00E5B0'],['mint','#5CF2A6'],['lime','#B6F36A'],['lemon','#FFE04A'],['amber','#FFC247'],
  ['coral','#FF8A65'],['rose','#FF6F91'],['pink','#FF6FD1'],['orchid','#D78BFF'],['violet','#B9A2FF'],['sky','#7AB8FF']];

// geometry in a 108×108 adaptive-icon canvas (safe zone = central 66)
const C=54,R=31;
const f=n=>n.toFixed(2);
function cookie(){let d='';for(let i=0;i<360;i+=2){const t=i*Math.PI/180;const r=R*(1+0.05*Math.cos(12*t))/1.05;d+=(i?'L':'M')+f(C+r*Math.cos(t))+','+f(C+r*Math.sin(t));}return d+'Z';}
// rounded rectangle centred at (cx,cy) in dumbbell space, rotated -35° about the icon centre, scaled to the cookie
function rrect(x,y,w,h,rad){
  const s=R/400,a=-35*Math.PI/180,pts=[];
  const corner=(cx,cy,start)=>{for(let k=0;k<=6;k++){const t=(start+k*15)*Math.PI/180;pts.push([cx+rad*Math.cos(t),cy+rad*Math.sin(t)]);}};
  corner(x+w-rad,y+rad,270);corner(x+w-rad,y+h-rad,0);corner(x+rad,y+h-rad,90);corner(x+rad,y+rad,180);
  return pts.map(([px,py],i)=>{const X=px*s,Y=py*s;const rx=X*Math.cos(a)-Y*Math.sin(a),ry=X*Math.sin(a)+Y*Math.cos(a);
    return(i?'L':'M')+f(C+rx)+','+f(C+ry);}).join('')+'Z';
}
const BELL=[rrect(-170,-26,340,52,26),rrect(-230,-110,62,220,31),rrect(168,-110,62,220,31),rrect(-290,-70,50,140,25),rrect(240,-70,50,140,25)].join('');
const dark=hex=>{const n=parseInt(hex.slice(1),16);return '#'+[n>>16&255,n>>8&255,n&255].map(v=>Math.round(v*0.12).toString(16).padStart(2,'0')).join('').toUpperCase();};
const vec=paths=>`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n${paths}</vector>\n`;

function write(res){
  const dr=path.join(res,'drawable'),any=path.join(res,'mipmap-anydpi-v26'),mm=path.join(res,'mipmap');
  for(const d of [dr,any,mm,path.join(res,'values')])fs.mkdirSync(d,{recursive:true});
  fs.writeFileSync(path.join(res,'values','icon_colors.xml'),'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n  <color name="ic_bg_black">#000000</color>\n</resources>\n');
  // themed-icon silhouette: cookie with the dumbbell cut out
  fs.writeFileSync(path.join(dr,'ic_mono.xml'),vec(`  <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="${cookie()}${BELL}"/>\n`));
  for(const [name,hex] of ACCENTS){
    fs.writeFileSync(path.join(dr,`ic_fg_${name}.xml`),vec(`  <path android:fillColor="${hex}" android:pathData="${cookie()}"/>\n  <path android:fillColor="${dark(hex)}" android:pathData="${BELL}"/>\n`));
    fs.writeFileSync(path.join(any,`ic_launcher_${name}.xml`),`<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/ic_bg_black"/>
  <foreground android:drawable="@drawable/ic_fg_${name}"/>
  <monochrome android:drawable="@drawable/ic_mono"/>
</adaptive-icon>
`);
    // older Android (before adaptive icons)
    fs.writeFileSync(path.join(mm,`ic_launcher_${name}.xml`),`<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
  <item android:drawable="@color/ic_bg_black"/>
  <item android:drawable="@drawable/ic_fg_${name}"/>
</layer-list>
`);
  }
}

// Manifest: the launcher entry moves from MainActivity to one alias per colour (only cyan enabled at first)
function manifest(m,pkg){
  if(m.includes('.Icon_cyan'))return m;
  m=m.replace(/<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*\/>\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>\s*<\/intent-filter>/,'');
  const aliases=ACCENTS.map(([name])=>`        <activity-alias
            android:name="${pkg}.Icon_${name}"
            android:targetActivity=".MainActivity"
            android:enabled="${name==='cyan'}"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_${name}"
            android:roundIcon="@mipmap/ic_launcher_${name}"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>`).join('\n');
  return m.replace('</application>',aliases+'\n    </application>');
}

module.exports={ACCENTS,write,manifest};
