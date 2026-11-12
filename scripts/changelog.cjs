// What's new, kept in changelog.json:
//   "unreleased": changes since the last release (add an entry with every change; "img" is optional)
//   "releases":   newest first, each {version, date, items:[{text, img?}]}
//
//   node scripts/changelog.cjs stamp 1.14
// moves "unreleased" into a new 1.14 entry (or uses the commit subjects if nothing was written)
// and writes release-notes.md for the GitHub release page. CI commits changelog.json back afterwards.
const fs=require('fs'),path=require('path'),{execSync}=require('child_process');
const root=path.join(__dirname,'..'),file=path.join(root,'changelog.json');
const RAW='https://raw.githubusercontent.com/yashgumaste/fitness-tracker/main/';
const [cmd,version]=process.argv.slice(2);
const c=JSON.parse(fs.readFileSync(file,'utf8'));

if(cmd==='stamp'){
  if(!version)throw new Error('usage: changelog.cjs stamp <version>');
  let items=(c.unreleased||[]).filter(i=>i&&i.text);
  if(!items.length){
    let last='';try{last=execSync("git describe --tags --abbrev=0 --match 'v*'",{cwd:root,stdio:['ignore','pipe','ignore']}).toString().trim();}catch(_){}
    items=execSync(`git log --no-merges --format=%s ${last?last+'..':''}HEAD`,{cwd:root}).toString().split('\n')
      .filter(s=>s&&!/^(CI:|Changelog:)/.test(s)).slice(0,20).map(text=>({text}));
  }
  const date=new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Kolkata'});
  c.releases=[{version,date,items},...(c.releases||[]).filter(r=>r.version!==version)];
  c.unreleased=[];
  fs.writeFileSync(file,JSON.stringify(c,null,2)+'\n');
  const notes=items.map(i=>`- ${i.text}`+(i.img?`\n\n  <img src="${RAW}${i.img}" width="260">\n`:'')).join('\n');
  fs.writeFileSync(path.join(root,'release-notes.md'),
`Download **FitnessTracker-${version}.apk** below and open it on your phone, or tap **Update** in the app.
Updates install over the old version and keep your data.

**What's new**
${notes}
`);
  console.log(`changelog: ${version} · ${items.length} item(s)`);
}else throw new Error('unknown command '+cmd);
