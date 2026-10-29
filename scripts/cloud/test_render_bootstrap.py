"""Fictional bootstrap/export/exec checks; no provider access or actual credentials."""
from pathlib import Path
import base64,importlib.util,json,os,shlex,shutil,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('render_export',ROOT/'scripts/cloud/prepare_render_test.py')
export=importlib.util.module_from_spec(spec);spec.loader.exec_module(export)
class RenderBootstrapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=Path(tempfile.mkdtemp(prefix='s2-render-bootstrap-'));cls.addClassCleanup(shutil.rmtree,cls.temp)
        home=os.environ.get('JAVA_HOME');cls.java=str(Path(home)/'bin/java') if home else shutil.which('java')
        javac=str(Path(home)/'bin/javac') if home else shutil.which('javac')
        cls.classes=cls.temp/'app/bootstrap';cls.classes.mkdir(parents=True)
        subprocess.run([javac,'-d',str(cls.classes),str(ROOT/'deploy/render/RenderConfigWriter.java')],check=True,capture_output=True)
        cls.ca=cls.temp/'fictional.crt'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=Fictional Render CA',
            '-keyout',str(cls.temp/'fictional.key'),'-out',str(cls.ca)],check=True,capture_output=True)
    def settings(self):
        return {'app.persistence.jdbc-url':'jdbc:postgresql://db.abcdefghijklmnopqrst.supabase.co:5432/postgres',
            'app.persistence.schema':'group33_fixture','app.persistence.username':'group33_fixture',
            'app.persistence.password':'FICTIONAL_PRIVATE_RENDER_PASSWORD_DO_NOT_LOG','app.jwt.secret':'FICTIONAL_PRIVATE_RENDER_JWT_COMPONENT_2026',
            'app.supabase.project-ref':'abcdefghijklmnopqrst','app.photo.supabase.origin':'https://abcdefghijklmnopqrst.supabase.co',
            'app.photo.supabase.bucket':'fictional-private'}
    def text(self):return ''.join(k+'='+v+'\n' for k,v in self.settings().items())
    def encode(self,value):return base64.b64encode(value).decode()
    def env(self):
        return {'PATH':os.environ['PATH'],'HOME':os.environ.get('HOME','/tmp'),'TMPDIR':str(self.temp),'PORT':'10000',
            'GROUP33_RENDER_SETTINGS_B64':self.encode(self.text().encode()),
            'GROUP33_RENDER_RECEIPT_KEY_B64':self.encode(bytes(range(32))),
            'GROUP33_RENDER_DATABASE_CA_B64':self.encode(self.ca.read_bytes()),
            'GROUP33_RENDER_STORAGE_SECRET':'sb_secret_FICTIONAL_PRIVATE_RENDER_STORAGE_COMPONENT_2026'}
    def writer(self,env):
        return subprocess.run([self.java,'-Djava.io.tmpdir='+str(self.temp),'-cp',str(self.classes),'RenderConfigWriter'],env=env,capture_output=True,text=True)
    def denied(self,env):
        r=self.writer(env);self.assertEqual(64,r.returncode);self.assertEqual('',r.stdout)
        for value in self.settings().values():self.assertNotIn(value,r.stderr)
        self.assertNotIn(env.get('GROUP33_RENDER_STORAGE_SECRET','never'),r.stderr)
        self.assertIn('private configuration is invalid',r.stderr)
    def test_original_binary_key_and_secrets_survive_owner_only_materialization(self):
        r=self.writer(self.env());self.assertEqual(0,r.returncode)
        path=Path(r.stdout.strip());self.assertEqual(0o700,path.parent.stat().st_mode&0o777)
        self.assertEqual(bytes(range(32)),(path.parent/'receipt.key').read_bytes())
        self.assertEqual(self.ca.read_bytes(),(path.parent/'database-ca.crt').read_bytes())
        self.assertEqual(self.env()['GROUP33_RENDER_STORAGE_SECRET'],(path.parent/'storage-secret.key').read_text())
        self.assertTrue(all(x.stat().st_mode&0o777==0o600 for x in path.parent.iterdir()))
        self.assertIn('app.persistence.mode=postgres-supabase',path.read_text());self.assertIn('app.plan.enabled=true',path.read_text())
    def test_missing_each_private_input_fails_before_files_are_created(self):
        for name in [k for k in self.env() if k.startswith('GROUP33_')]:
            with self.subTest(name=name):
                env=self.env();del env[name];before=set(self.temp.glob('group33-render-*'));self.denied(env);self.assertEqual(before,set(self.temp.glob('group33-render-*')))
    def test_malformed_and_oversized_settings_never_reach_the_server(self):
        for value in ('not-base64',self.encode(b'x'*16385),self.encode(b'\xff')):
            with self.subTest(value_length=len(value)):
                env=self.env();env['GROUP33_RENDER_SETTINGS_B64']=value;self.denied(env)
    def test_configuration_cannot_switch_to_memory_or_override_bootstrap_paths(self):
        for suffix in ('app.persistence.mode=memory\n','app.persistence.receipt-key-file=/tmp/other\n','server.address=127.0.0.1\n'):
            env=self.env();env['GROUP33_RENDER_SETTINGS_B64']=self.encode((self.text()+suffix).encode());self.denied(env)
    def test_duplicate_private_settings_are_not_silently_reinterpreted(self):
        env=self.env();env['GROUP33_RENDER_SETTINGS_B64']=self.encode((self.text()+'app.persistence.password=other\n').encode());self.denied(env)
    def test_receipt_key_wrong_size_invalid_encoding_and_noncanonical_base64_fail(self):
        for value in (self.encode(b'x'*31),self.encode(b'x'*33),'bad!',self.env()['GROUP33_RENDER_RECEIPT_KEY_B64'].rstrip('=')):
            env=self.env();env['GROUP33_RENDER_RECEIPT_KEY_B64']=value;self.denied(env)
    def test_invalid_certificate_and_storage_key_fail_without_disclosure(self):
        for name,value in [('GROUP33_RENDER_DATABASE_CA_B64',self.encode(b'not a certificate')),
            ('GROUP33_RENDER_STORAGE_SECRET','sb_publishable_FICTIONAL_COMPONENT_2026')]:
            env=self.env();env[name]=value;self.denied(env)
    def test_port_is_bounded_for_unprivileged_runtime(self):
        for port in ('0','80','65536','-1','010000','10000;echo secret'):
            env=self.env();env['PORT']=port;self.denied(env)
    def fixture_private(self):
        path=Path(tempfile.mkdtemp(dir=self.temp));os.chmod(path,0o700)
        for name,data in [('application.properties',self.text().encode()),('receipt.key',bytes(range(32))),
            ('database-ca.crt',self.ca.read_bytes()),('storage-secret.key',self.env()['GROUP33_RENDER_STORAGE_SECRET'].encode())]:
            (path/name).write_bytes(data);(path/name).chmod(0o600)
        return path
    def test_export_is_private_repeatable_and_does_not_rotate_original_material(self):
        path=self.fixture_private();before={p.name:p.read_bytes() for p in path.iterdir()}
        output=export.prepare(path);first=output.read_bytes();self.assertEqual(0o600,output.stat().st_mode&0o777)
        self.assertEqual(first,export.prepare(path).read_bytes())
        self.assertEqual(before,{p.name:p.read_bytes() for p in path.iterdir() if p.name!='render.env'})
        values=dict(line.split('=',1) for line in output.read_text().splitlines())
        self.assertEqual(bytes(range(32)),base64.b64decode(values['GROUP33_RENDER_RECEIPT_KEY_B64']))
    def test_export_rejects_changed_or_symlink_output_and_wide_input(self):
        for kind in ('changed','symlink','wide'):
            path=self.fixture_private();output=path/'render.env'
            if kind=='changed':output.write_text('existing');output.chmod(0o600)
            elif kind=='symlink':output.symlink_to(path/'receipt.key')
            else:(path/'receipt.key').chmod(0o644)
            with self.assertRaises(ValueError):export.prepare(path)
            self.assertEqual(bytes(range(32)),(path/'receipt.key').read_bytes())
    def test_launcher_exec_preserves_exit_and_removes_private_and_injected_java_environment(self):
        directory=self.temp/'bin';directory.mkdir();probe=self.temp/'probe.py';record=self.temp/'child.json'
        probe.write_text('import os,sys,json\nfrom pathlib import Path\nPath('+repr(str(record))+').write_text(json.dumps({"pid":os.getpid(),"env":list(os.environ),"args":sys.argv[1:]}))\nsys.exit(23)\n')
        wrapper='#!/bin/sh\ncase "$*" in *RenderConfigWriter*) exec '+shlex.quote(self.java)+' "$@" ;; esac\nexec '+shlex.quote(os.sys.executable)+' '+shlex.quote(str(probe))+' "$@"\n'
        (directory/'java').write_text(wrapper);(directory/'java').chmod(0o700)
        env=self.env();env.update(PATH=str(directory)+':'+os.environ['PATH'],GROUP33_APP_DIR=str(self.classes.parent),GROUP33_BIND_ADDRESS='127.0.0.1',
            JAVA_TOOL_OPTIONS='FICTIONAL_INJECTED_JAVA_VALUE',SPRING_APPLICATION_JSON='FICTIONAL_INJECTED_SPRING_VALUE')
        child=subprocess.Popen(['sh',str(ROOT/'deploy/render/start.sh')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        stdout,stderr=child.communicate(timeout=20);self.assertEqual(23,child.returncode,stderr);self.assertEqual('',stdout)
        result=json.loads(record.read_text());self.assertEqual(child.pid,result['pid'])
        self.assertFalse(any(k.startswith('GROUP33_') or k in {'JAVA_TOOL_OPTIONS','SPRING_APPLICATION_JSON'} for k in result['env']))
        for value in self.settings().values():self.assertNotIn(value,' '.join(result['args'])+stdout+stderr)
    def test_launcher_rejects_unexpected_bind_address(self):
        env=self.env();env['GROUP33_BIND_ADDRESS']='example.invalid'
        r=subprocess.run(['sh',str(ROOT/'deploy/render/start.sh')],env=env,capture_output=True,text=True)
        self.assertEqual(64,r.returncode);self.assertEqual('',r.stdout)
if __name__=='__main__':unittest.main()
