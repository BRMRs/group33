"""Prepare an isolated Supabase test schema/configuration locally. Never sends requests or prints secrets."""
from pathlib import Path
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import ssl
import stat
import sys

PROJECT = Path(__file__).resolve().parents[2]
MIGRATIONS = [
    'pairing-code-store-v1.sql', 'pairing-request-store-v2.sql', 'pairing-decision-store-v3.sql',
    'pairing-directory-store-v4.sql', 'anonymous-identity-store-v5.sql', 'medication-photo-store-v6.sql',
    'plan-confirmation-store-v7.sql', 'plan-mutation-store-v8.sql',
]

def write_private(path, value):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(path, flags, 0o600), 'wb') as output:
        output.write(value if isinstance(value, bytes) else value.encode('utf-8'))

def private_directory(path):
    path = Path(path).expanduser().absolute()
    if path.is_symlink() or path.resolve().is_relative_to(PROJECT):
        raise ValueError('Use a private directory outside the source checkout')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('The private directory must belong to you and have permission 700')
    return path

def prepare(path):
    path = private_directory(path)
    if not (path/'.gitignore').exists(): write_private(path/'.gitignore', '*\n')
    if (path/'settings-input.json').exists(): return path
    write_private(path/'settings-input.json', json.dumps(dict(
        project_ref='', database_host='', storage_bucket='group33-s2-test-photos',
        storage_secret_key='', ca_certificate_file=str(path/'supabase-ca.crt')), indent=2)+'\n')
    return path

def validate(data):
    if set(data) != {'project_ref','database_host','storage_bucket','storage_secret_key','ca_certificate_file'}:
        raise ValueError('Input field names do not match the template')
    if not all(isinstance(v,str) for v in data.values()): raise ValueError('All input fields must be text')
    project = data['project_ref']; host = data['database_host']
    if not re.fullmatch('[a-z0-9]{20}',project): raise ValueError('Fill project_ref from the project settings')
    direct = host == 'db.'+project+'.supabase.co'
    if not direct and not re.fullmatch(r'aws-[0-9]+-[a-z0-9-]+\.pooler\.supabase\.com',host):
        raise ValueError('Use the direct or shared session-pooler hostname from Connect, without a URL or port')
    if not re.fullmatch('[a-z0-9][a-z0-9_-]{0,62}',data['storage_bucket']): raise ValueError('Check the private bucket ID')
    if not re.fullmatch('sb_secret_[A-Za-z0-9_-]{16,256}',data['storage_secret_key']):
        raise ValueError('Use a backend sb_secret_ key, saved only in this private input file')
    cert = Path(data['ca_certificate_file'])
    if not cert.is_absolute() or cert.is_symlink() or not cert.is_file() or not 0 < cert.stat().st_size <= 65536:
        raise ValueError('Save the project database CA certificate to the indicated file')
    ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT).load_verify_locations(cafile=str(cert))
    return direct,cert.read_bytes()

def properties(values):
    # Generated secrets are URL-safe. Escape all values as Java properties, including non-ASCII file paths.
    def escape(value):
        result=''
        for c in str(value):
            if c in '\\:=#! ': result+='\\'+c
            elif c in '\r\n\t': result+={'\r':'\\r','\n':'\\n','\t':'\\t'}[c]
            elif ord(c)>127:
                result+=''.join('\\u'+format(int.from_bytes(pair,'big'),'04x') for pair in
                    [c.encode('utf-16-be')[i:i+2] for i in range(0,len(c.encode('utf-16-be')),2)])
            else: result+=c
        return result
    return ''.join(name+'='+escape(value)+'\n' for name,value in values.items())

def scram_verifier(password):
    salt=secrets.token_bytes(16); rounds=4096
    salted=hashlib.pbkdf2_hmac('sha256',password.encode('utf-8'),salt,rounds)
    client=hmac.new(salted,b'Client Key',hashlib.sha256).digest()
    stored=hashlib.sha256(client).digest();server=hmac.new(salted,b'Server Key',hashlib.sha256).digest()
    encode=lambda value:base64.b64encode(value).decode('ascii')
    return 'SCRAM-SHA-256$'+str(rounds)+':'+encode(salt)+'$'+encode(stored)+':'+encode(server)

def qualify_prototype(source,schema,names):
    """Render only trusted prototype identifiers, preserving comments/JSON strings and function bodies."""
    result=[];i=0
    while i<len(source):
        if source.startswith('--',i):
            end=source.find('\n',i);end=len(source) if end<0 else end
            result.append(source[i:end]);i=end;continue
        if source.startswith('/*',i):
            end=i+2;depth=1
            while end<len(source) and depth:
                if source.startswith('/*',end):depth+=1;end+=2
                elif source.startswith('*/',end):depth-=1;end+=2
                else:end+=1
            if depth:raise ValueError('Unterminated prototype comment')
            result.append(source[i:end]);i=end;continue
        if source[i] in "'\"":
            quote=source[i];end=i+1
            while end<len(source):
                if source[end]==quote:
                    if end+1<len(source) and source[end+1]==quote:end+=2;continue
                    break
                end+=1
            if end==len(source):raise ValueError('Unterminated prototype literal')
            literal=source[i:end+1];value=literal[1:-1].replace(quote+quote,quote)
            if value in names and (quote=='"' or re.match(r'\s*::\s*(?:pg_catalog\.)?regclass\b',source[end+1:])):
                literal=(schema+'.'+literal) if quote=='"' else "'"+schema+'.'+value+"'"
            result.append(literal);i=end+1;continue
        if source[i]=='$':
            match=re.match(r'\$[A-Za-z_0-9]*\$',source[i:])
            if match:
                tag=match.group();start=i+len(tag);end=source.find(tag,start)
                if end<0:raise ValueError('Unterminated prototype body')
                result.extend([tag,qualify_prototype(source[start:end],schema,names),tag]);i=end+len(tag);continue
        match=re.match(r'[A-Za-z_][A-Za-z_0-9$]*',source[i:])
        if match:
            word=match.group();prefix=source[:i].rstrip()
            result.append(schema+'.'+word if word in names and not prefix.endswith('.') else word)
            i+=len(word);continue
        result.append(source[i]);i+=1
    return ''.join(result)

def initialization_sql(schema,role,password):
    assert re.fullmatch('group33_s2_[a-f0-9]{12}',schema)
    assert re.fullmatch('group33_app_[a-f0-9]{12}',role)
    assert re.fullmatch('[A-Za-z0-9_-]{32,128}',password)
    sources=[(name,(PROJECT/'server/src/main/resources/db/prototypes'/name).read_bytes()) for name in MIGRATIONS]
    names=set()
    for _,source in sources:
        names.update(re.findall(r'CREATE TABLE IF NOT EXISTS ([a-z_][a-z_0-9]*)',source.decode()))
        names.update(re.findall(r'CREATE(?: OR REPLACE)? FUNCTION ([a-z_][a-z_0-9]*)',source.decode()))
    sql=['CREATE ROLE '+role+" LOGIN PASSWORD '"+scram_verifier(password)+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;",
         "DO $database_connect$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '"+role+"'); END $database_connect$;",
         'CREATE SCHEMA '+schema+';', 'REVOKE ALL ON SCHEMA '+schema+' FROM PUBLIC;',
         'SET LOCAL search_path TO '+schema+', pg_catalog;']
    for name,source in sources:
        sql+=['-- '+name+' SHA256 '+hashlib.sha256(source).hexdigest(),qualify_prototype(source.decode('utf-8'),schema,names)]
    sql += ['REVOKE ALL ON ALL TABLES IN SCHEMA '+schema+' FROM PUBLIC;',
            'REVOKE ALL ON ALL SEQUENCES IN SCHEMA '+schema+' FROM PUBLIC;',
            'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA '+schema+' FROM PUBLIC;',
            "DO $private_grants$ DECLARE api_role text; BEGIN FOR api_role IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated','service_role') LOOP "
            "EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', '"+schema+"', api_role); "
            "EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', '"+schema+"', api_role); "
            "EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', '"+schema+"', api_role); "
            "EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM %I', '"+schema+"', api_role); END LOOP; END $private_grants$;",
            'GRANT USAGE ON SCHEMA '+schema+' TO '+role+';',
            'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA '+schema+' TO '+role+';',
            'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA '+schema+' TO '+role+';',
            'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA '+schema+' TO '+role+';']
    return ('-- PRIVATE: contains a test role password verifier. Never commit or share.\n'
            'DO $s2_initialize$\nBEGIN\n'+'\n'.join(sql)+'\nEND\n$s2_initialize$;\n')

def configure(path):
    path = private_directory(path)
    source = path/'settings-input.json'
    if source.is_symlink() or source.stat().st_mode & 0o077: raise ValueError('Private input file must have permission 600')
    data=json.loads(source.read_text()); direct,certificate=validate(data)
    outputs=['storage-secret.key','receipt.key','database-ca.crt','application.properties','initialize-test-schema.sql']
    if any((path/name).exists() or (path/name).is_symlink() for name in outputs):
        raise ValueError('Private output already exists; preserve the original keys/settings and do not regenerate them')
    suffix=secrets.token_hex(6); schema='group33_s2_'+suffix; role='group33_app_'+suffix
    password=secrets.token_urlsafe(48)
    values={'server.address':'127.0.0.1','server.port':'18086','app.persistence.mode':'postgres-supabase',
            'app.persistence.jdbc-url':'jdbc:postgresql://'+data['database_host']+':5432/postgres',
            'app.persistence.schema':schema,'app.persistence.username':role if direct else role+'.'+data['project_ref'],
            'app.persistence.password':password,'app.persistence.ssl-root-cert':str(path/'database-ca.crt'),
            'app.persistence.receipt-key-file':str(path/'receipt.key'),'app.jwt.secret':secrets.token_urlsafe(48),
            'app.supabase.project-ref':data['project_ref'],'app.photo.enabled':'true','app.photo.storage-mode':'supabase',
            'app.photo.supabase.origin':'https://'+data['project_ref']+'.supabase.co',
            'app.photo.supabase.bucket':data['storage_bucket'],
            'app.photo.supabase.secret-key-file':str(path/'storage-secret.key'),'app.plan.enabled':'true'}
    # Every write is exclusive: a rerun cannot rotate an in-use identity/receipt key or alter old settings.
    write_private(path/'storage-secret.key',data['storage_secret_key']+'\n')
    write_private(path/'receipt.key',secrets.token_bytes(32)); write_private(path/'database-ca.crt',certificate)
    write_private(path/'application.properties',properties(values))
    write_private(path/'initialize-test-schema.sql',initialization_sql(schema,role,password))
    return path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','configure']); parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    try:
        path=(prepare if args.action=='prepare' else configure)(args.directory)
        print('Private '+args.action+' complete: '+str(path))
        print('No network request was sent and no database/bucket was created.')
    except Exception:
        # Provider keys, JSON parsing context, certificate errors and paths can include private input.
        print('Private setup incomplete. Check the local input template, certificate and owner-only permissions; existing output is never overwritten.',file=sys.stderr)
        return 1
    return 0

if __name__=='__main__': sys.exit(main())
