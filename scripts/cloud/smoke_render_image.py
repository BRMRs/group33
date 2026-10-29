"""CI-only final-JRE image check with fictional in-memory data; never cloud acceptance."""
import binascii,json,struct,sys,time,urllib.error,urllib.parse,urllib.request,uuid,zlib
base=sys.argv[1];url=urllib.parse.urlparse(base)
assert url.scheme=='http' and url.hostname=='127.0.0.1' and url.path in ('','/')
def call(status,path,body=None,token=None,operation=None,kind='application/json'):
    headers={'Content-Type':kind}
    if token:headers['Authorization']='Bearer '+token
    if operation:headers['Idempotency-Key']=operation
    if isinstance(body,dict):body=json.dumps(body).encode()
    request=urllib.request.Request(base+path,data=body,headers=headers)
    with urllib.request.urlopen(request,timeout=10) as response:
        assert response.status==status,'Fictional image fixture returned unexpected HTTP status'
        return json.load(response) if response.headers.get('Content-Type','').startswith('application/json') else response.read()
deadline=time.monotonic()+45
while True:
    try:
        if call(200,'/health')['status']=='ok':break
    except (OSError,urllib.error.URLError):
        if time.monotonic()>=deadline:raise RuntimeError('Final image did not become healthy')
        time.sleep(.2)
operation=lambda:str(uuid.uuid4())
elder=call(201,'/api/v1/auth/anonymous',dict(installationId=operation(),displayName='Fictional CI Elder',role='ELDER'))
caregiver=call(201,'/api/v1/auth/anonymous',dict(installationId=operation(),displayName='Fictional CI Caregiver',role='CAREGIVER'))
et=elder['accessToken'];ct=caregiver['accessToken']
code=call(201,'/api/v1/pairing-codes',{},et,operation())
pending=call(201,'/api/v1/pairing-requests',dict(code=code['code']),ct,operation())
call(200,'/api/v1/pairing-requests/'+pending['id']+'/accept',{},et,operation())
def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',binascii.crc32(kind+data)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(bytes([0,80,0,0])))+chunk(b'IEND',b'')
boundary='fictional-'+uuid.uuid4().hex
multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fictional.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png+f'\r\n--{boundary}--\r\n'.encode())
elder_path='/api/v1/elders/'+elder['user']['id']
photo=call(201,elder_path+'/medication-photos',multipart,et,operation(),'multipart/form-data; boundary='+boundary)
assert call(200,'/api/v1/medication-photos/'+photo['id']+'/content',token=ct)==png
saved=call(201,elder_path+'/reminder-plan-confirmations',dict(photoId=photo['id'],
    medication=dict(displayName='Fictional CI medication',dosageText='Fictional dose',instructions=None),
    plan=dict(localTimes=['08:00'],startDate='2026-11-01',executionTimeZoneId='Asia/Shanghai',endDate=None),
    timeZoneAcknowledgement=dict(executionTimeZoneId='Asia/Shanghai',mismatchAcknowledged=False,caregiverTimeZoneId='Asia/Shanghai',observedElderTimeZoneId='Asia/Shanghai')),ct,operation())
assert saved['plan']['revision']==1 and saved['plan']['medicationId']==saved['medication']['id']
print(json.dumps(dict(passed=True,memory_fixture=True,cloud_provider=False,final_jre_photo_and_plan=True)))
