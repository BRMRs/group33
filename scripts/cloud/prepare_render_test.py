"""Export the existing isolated cloud test settings for Render. Never prints or rotates secrets."""
from pathlib import Path
import argparse,base64,os,stat,sys
PROJECT=Path(__file__).resolve().parents[2]
KEYS={'app.persistence.jdbc-url','app.persistence.schema','app.persistence.username','app.persistence.password',
      'app.jwt.secret','app.supabase.project-ref','app.photo.supabase.origin','app.photo.supabase.bucket'}
def prepare(directory):
    directory=Path(directory).expanduser().absolute()
    if directory.is_symlink() or directory.resolve().is_relative_to(PROJECT):raise ValueError()
    for path in [directory,*[directory/n for n in ('application.properties','receipt.key','database-ca.crt','storage-secret.key')]]:
        info=path.lstat()
        if path.is_symlink() or info.st_uid!=os.getuid() or info.st_mode&0o077:raise ValueError()
    lines=[line for line in (directory/'application.properties').read_text().splitlines() if line.split('=',1)[0] in KEYS]
    if len(lines)!=len(KEYS) or {line.split('=',1)[0] for line in lines}!=KEYS:raise ValueError()
    receipt=(directory/'receipt.key').read_bytes()
    if len(receipt)!=32:raise ValueError()
    encode=lambda data:base64.b64encode(data).decode('ascii')
    values={'GROUP33_RENDER_SETTINGS_B64':encode(('\n'.join(lines)+'\n').encode()),
        'GROUP33_RENDER_RECEIPT_KEY_B64':encode(receipt),
        'GROUP33_RENDER_DATABASE_CA_B64':encode((directory/'database-ca.crt').read_bytes()),
        'GROUP33_RENDER_STORAGE_SECRET':(directory/'storage-secret.key').read_text().strip()}
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
