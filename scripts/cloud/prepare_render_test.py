"""Export the existing isolated cloud test settings for Render. Never prints or rotates secrets."""
from pathlib import Path
import argparse,base64,os,stat,sys
PROJECT=Path(__file__).resolve().parents[2]
KEYS={'app.persistence.jdbc-url','app.persistence.schema','app.persistence.username','app.persistence.password',
      'app.jwt.secret','app.supabase.project-ref','app.photo.supabase.origin','app.photo.supabase.bucket'}
SCHEMA_GATE='app.persistence.require-versioned-schema'
PHONE='app.bound-phone.enabled'
PHONE_REQUIRED={'app.bound-phone.jwt-issuer','app.bound-phone.jwt-audience','app.bound-phone.access-key-id',
    'app.bound-phone.access-key-secret','app.bound-phone.scheme-code','app.bound-phone.android-package',
    'app.bound-phone.android-signature','app.bound-phone.trusted-proxy-cidrs'}
PHONE_OPTIONAL={'app.bound-phone.session-seconds','app.bound-phone.clock-allowance-seconds',
    'app.bound-phone.requests-per-minute','app.bound-phone.max-tracked-clients'}
# These original local-fixture settings are deliberately selected by the Render bootstrap instead.
BOOTSTRAP_KEYS={'server.address','server.port','app.persistence.mode','app.persistence.ssl-root-cert',
            'app.persistence.receipt-key-file','app.photo.enabled','app.photo.storage-mode',
            'app.photo.supabase.secret-key-file','app.plan.enabled','app.bound-phone.jwt-key-file',
            'app.bound-phone.phone-hash-key-file','app.fusion.enabled','server.forward-headers-strategy'}
def settings_lines(raw):
    lines=[];gate=[];seen=set();flags={}
    for line in raw:
        if not line.strip() or line.lstrip(' \t\f').startswith(('#','!')):continue
        key,separator,value=line.partition('=')
        # Accept the generator's literal, single-line key=value format, not an incomplete
        # Java Properties parser that could discard escaped keys, aliases or continuations.
        if not separator or key not in KEYS|BOOTSTRAP_KEYS|PHONE_REQUIRED|PHONE_OPTIONAL|{SCHEMA_GATE,PHONE} or key in seen:raise ValueError()
        if (len(line)-len(line.rstrip('\\')))%2:raise ValueError()
        seen.add(key)
        if key in {SCHEMA_GATE,PHONE}:
            if value not in ('true','false'):raise ValueError()
            gate.append(line);flags[key]=value
        elif key in KEYS|PHONE_REQUIRED|PHONE_OPTIONAL:lines.append(line)
    if not KEYS<=seen:raise ValueError()
    if flags.get(PHONE)=='true':
        if not PHONE_REQUIRED<=seen or flags.get(SCHEMA_GATE)!='true':raise ValueError()
    elif seen&(PHONE_REQUIRED|PHONE_OPTIONAL):raise ValueError()
    return lines+gate
def prepare(directory):
    directory=Path(directory).expanduser().absolute()
    if directory.is_symlink() or directory.resolve().is_relative_to(PROJECT):raise ValueError()
    for path in [directory,*[directory/n for n in ('application.properties','receipt.key','database-ca.crt','storage-secret.key')]]:
        info=path.lstat()
        if path.is_symlink() or info.st_uid!=os.getuid() or info.st_mode&0o077:raise ValueError()
    lines=settings_lines((directory/'application.properties').read_text().split('\n'))
    phone=PHONE+'=true' in lines
    if phone:
        for name in ('phone-jwt.key','phone-hash.key'):
            path=directory/name;info=path.lstat()
            if not stat.S_ISREG(info.st_mode) or path.is_symlink() or info.st_uid!=os.getuid() or info.st_mode&0o077:raise ValueError()
    receipt=(directory/'receipt.key').read_bytes()
    if len(receipt)!=32:raise ValueError()
    encode=lambda data:base64.b64encode(data).decode('ascii')
    values={'GROUP33_RENDER_SETTINGS_B64':encode(('\n'.join(lines)+'\n').encode()),
        'GROUP33_RENDER_RECEIPT_KEY_B64':encode(receipt),
        'GROUP33_RENDER_DATABASE_CA_B64':encode((directory/'database-ca.crt').read_bytes()),
        'GROUP33_RENDER_STORAGE_SECRET':(directory/'storage-secret.key').read_text().strip()}
    if phone:
        jwt=(directory/'phone-jwt.key').read_bytes();phone_hash=(directory/'phone-hash.key').read_bytes()
        if len(jwt)!=32 or len(phone_hash)!=32 or len({receipt,jwt,phone_hash})!=3:raise ValueError()
        values.update(GROUP33_RENDER_PHONE_JWT_KEY_B64=encode(jwt),GROUP33_RENDER_PHONE_HASH_KEY_B64=encode(phone_hash))
    if any('\n' in v or '\r' in v for v in values.values()):raise ValueError()
    target=directory/'render.env';data=''.join(k+'='+v+'\n' for k,v in values.items()).encode()
    if target.exists() or target.is_symlink():
        if target.is_symlink() or target.stat().st_mode&0o077 or target.read_bytes()!=data:raise ValueError()
    else:
        with os.fdopen(os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600),'wb') as f:f.write(data)
    return target
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);args=p.parse_args()
    try:
        target=prepare(args.directory);print('Private Render import prepared: '+str(target));print('Import only into the isolated test service; never share or commit this file.')
        return 0
    except Exception:
        print('Private Render export incomplete; verify original owner-only settings. Existing output is never replaced.',file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
