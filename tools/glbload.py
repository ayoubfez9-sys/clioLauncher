import json,struct
import numpy as np
def load(path):
    d=open(path,'rb').read()
    jl=struct.unpack('<I',d[12:16])[0];j=json.loads(d[20:20+jl]);bin0=20+jl+8
    binlen=struct.unpack('<I',d[20+jl:24+jl])[0]
    return d,j,bin0,binlen
def acc_arr(d,j,bin0,i):
    a=j['accessors'][i];bv=j['bufferViews'][a['bufferView']]
    off=bin0+bv.get('byteOffset',0)+a.get('byteOffset',0)
    n={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}[a['type']];dt={5126:'<f4',5123:'<u2',5125:'<u4',5121:'u1'}[a['componentType']]
    st=bv.get('byteStride',0)
    arr=np.frombuffer(d,dtype=dt,count=a['count']*n,offset=off).reshape(-1,n) if st in (0,n*np.dtype(dt).itemsize) else None
    return arr
def mat(n):
    if 'matrix' in n: return np.array(n['matrix']).reshape(4,4).T
    return np.eye(4)
def parts(path):
    d,j,b0,bl=load(path);out=[]
    def walk(i,P):
        n=j['nodes'][i];W=P@mat(n)
        if 'mesh' in n:
            for pi,pr in enumerate(j['meshes'][n['mesh']]['primitives']):
                v=acc_arr(d,j,b0,pr['attributes']['POSITION']);nr=acc_arr(d,j,b0,pr['attributes']['NORMAL']);idx=acc_arr(d,j,b0,pr['indices']).reshape(-1)
                out.append(dict(node=n['name'],mesh=n['mesh'],prim=pi,mat=j['materials'][pr['material']]['name'],W=W,v=v,n=nr,idx=idx))
        for c in n.get('children',[]): walk(c,W)
    for r in j['scenes'][0]['nodes']: walk(r,np.eye(4))
    allv=np.vstack([(p['W']@np.c_[p['v'],np.ones(len(p['v']))].T).T[:,:3] for p in parts_list(out)])
    mn,mx=allv.min(0),allv.max(0);s=4.0/(mx[2]-mn[2]);ctr=np.array([(mn[0]+mx[0])/2,mn[1],(mn[2]+mx[2])/2])
    for p in out:
        w=(p['W']@np.c_[p['v'],np.ones(len(p['v']))].T).T[:,:3];p['L']=(w-ctr)*s
        R=p['W'][:3,:3];nn=(R@p['n'].T).T;p['N']=nn/np.maximum(np.linalg.norm(nn,axis=1,keepdims=True),1e-9)
    return d,j,b0,bl,out
def parts_list(o): return o
