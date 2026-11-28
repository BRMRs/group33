"""Local settings generation tests; no provider or network access."""
from pathlib import Path
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('setup',Path(__file__).with_name('prepare_supabase_test.py'))
setup=importlib.util.module_from_spec(spec); spec.loader.exec_module(setup)

class SupabasePrivateSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp=Path(tempfile.mkdtemp(prefix='s2-cloud-settings-')); self.addCleanup(shutil.rmtree,self.temp)
        self.private=self.temp/'private'; setup.prepare(self.private)
    def data(self):
        data=json.loads((self.private/'settings-input.json').read_text())
        data.update(project_ref='abcdefghijklmnopqrst',database_host='db.abcdefghijklmnopqrst.supabase.co',
            storage_secret_key='sb_secret_FICTIONAL_TEST_ONLY_COMPONENT_2026')
        return data
    def certificate(self):
        # Test-only short-lived fictional CA. OpenSSL never sees a real password or provider key.
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=Fictional Test CA',
            '-keyout',str(self.temp/'fictional.key'),'-out',str(self.private/'supabase-ca.crt')],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def save(self,data): (self.private/'settings-input.json').write_text(json.dumps(data))
    def test_template_is_private_and_existing_input_is_preserved(self):
        self.assertEqual(0o700,self.private.stat().st_mode&0o777)
        self.assertEqual(0o600,(self.private/'settings-input.json').stat().st_mode&0o777)
        before=(self.private/'settings-input.json').read_bytes(); setup.prepare(self.private)
        self.assertEqual(before,(self.private/'settings-input.json').read_bytes())
        self.assertEqual('*\n',(self.private/'.gitignore').read_text())
    def test_no_generation_until_input_and_certificate_are_complete(self):
        with self.assertRaises(ValueError): setup.configure(self.private)
        self.assertFalse((self.private/'receipt.key').exists())
    def test_secret_url_and_input_type_rejection_happens_before_writes(self):
        for field,value in [('project_ref','bad'),('database_host','http://example.invalid'),
            ('database_host','aws-0-us-east-1.pooler.supabase.com:6543'),('storage_bucket','../public'),
            ('storage_secret_key','sb_publishable_FICTIONAL_TEST_ONLY'),('project_ref',False)]:
            with self.subTest(field=field,value=value):
                data=self.data(); data[field]=value; self.save(data)
                with self.assertRaises(ValueError): setup.configure(self.private)
                self.assertFalse((self.private/'receipt.key').exists())
    def test_new_schema_private_role_and_no_runtime_DDL_are_generated_once(self):
        self.certificate(); self.save(self.data()); setup.configure(self.private)
        props=(self.private/'application.properties').read_text()
        sql=(self.private/'initialize-test-schema.sql').read_text()
        password=next(line.split('=',1)[1] for line in props.splitlines() if line.startswith('app.persistence.password='))
        self.assertTrue(password not in sql)
        self.assertTrue('SCRAM-SHA-256$4096:' in sql)
        self.assertTrue(sql.startswith('-- PRIVATE:') and '\nDO $s2_initialize$\nBEGIN\n' in sql)
        self.assertTrue(sql.endswith('\nEND\n$s2_initialize$;\n') and '\nCOMMIT;' not in sql)
        self.assertIn('app.persistence.mode=postgres-supabase',props)
        self.assertIn('server.address=127.0.0.1',props)
        self.assertIn('NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',sql)
        self.assertEqual(8,sql.count(' SHA256 ')); self.assertIn('REVOKE ALL ON ALL TABLES',sql)
        self.assertIn('GRANT SELECT, INSERT, UPDATE, DELETE',sql)
        self.assertTrue('GRANT CREATE' not in sql)
        self.assertFalse(bool(re.search(r'\bDROP\s+(?:SCHEMA|TABLE|ROLE)\b',sql)))
        self.assertEqual(32,len((self.private/'receipt.key').read_bytes()))
        originals={p.name:p.read_bytes() for p in self.private.iterdir() if p.is_file()}
        with self.assertRaises(ValueError): setup.configure(self.private)
        self.assertEqual(originals,{p.name:p.read_bytes() for p in self.private.iterdir() if p.is_file()})
        self.assertTrue(all((p.stat().st_mode&0o777)==0o600 for p in self.private.iterdir() if p.name != 'supabase-ca.crt'))
    def test_session_pooler_app_role_has_the_exact_project_suffix(self):
        self.certificate(); data=self.data(); data['database_host']='aws-1-ap-southeast-1.pooler.supabase.com'
        self.save(data); setup.configure(self.private)
        props=(self.private/'application.properties').read_text()
        user=next(line for line in props.splitlines() if line.startswith('app.persistence.username='))
        self.assertTrue(user.endswith('.abcdefghijklmnopqrst'))
    def test_private_input_and_directory_permissions_are_enforced(self):
        (self.private/'settings-input.json').chmod(0o644)
        with self.assertRaises(ValueError): setup.configure(self.private)
        self.private.chmod(0o755)
        with self.assertRaises(ValueError): setup.prepare(self.private)
    def test_existing_output_and_symlink_are_never_followed_or_overwritten(self):
        self.certificate(); self.save(self.data()); victim=self.temp/'original'; victim.write_text('original')
        (self.private/'receipt.key').symlink_to(victim)
        with self.assertRaises(ValueError): setup.configure(self.private)
        self.assertEqual('original',victim.read_text()); self.assertFalse((self.private/'storage-secret.key').exists())
    def test_properties_escape_paths_without_exposing_or_reinterpreting_values(self):
        value=setup.properties({'name':' /资料/a:b\\c\n'})
        self.assertEqual('name=\\ /\\u8d44\\u6599/a\\:b\\\\c\\n\n',value)

    def test_qualified_schema_preserves_json_strings_comments_and_regclass_identity(self):
        sql="-- medication stays in comment\nCREATE TABLE medication(id text);\nINSERT INTO medication VALUES ('medication');\nSELECT 'medication'::regclass, '{medication,id}';"
        rendered=setup.qualify_prototype(sql,'group33_fixture',{'medication'})
        self.assertIn('-- medication stays in comment',rendered)
        self.assertIn('CREATE TABLE group33_fixture.medication',rendered)
        self.assertIn("INSERT INTO group33_fixture.medication VALUES ('medication')",rendered)
        self.assertIn("'group33_fixture.medication'::regclass",rendered)
        self.assertIn("'{medication,id}'",rendered)

    def test_function_bodies_and_quoted_identifiers_are_qualified_without_cross_schema_rewrite(self):
        sql='CREATE FUNCTION p0_check() RETURNS trigger AS $fn$ BEGIN PERFORM 1 FROM "medication"; PERFORM 1 FROM other.medication; RETURN NEW; END $fn$ LANGUAGE plpgsql;'
        rendered=setup.qualify_prototype(sql,'group33_fixture',{'medication','p0_check'})
        self.assertIn('CREATE FUNCTION group33_fixture.p0_check()',rendered)
        self.assertIn('FROM group33_fixture."medication"',rendered)
        self.assertIn('FROM other.medication',rendered)

    def test_incomplete_trusted_prototype_is_rejected_before_becoming_a_misleading_initializer(self):
        for sql in ("SELECT 'medication",'/* unclosed', 'DO $fn$ BEGIN'):
            with self.subTest(sql=sql):
                with self.assertRaises(ValueError):setup.qualify_prototype(sql,'group33_fixture',{'medication'})

if __name__=='__main__': unittest.main()
