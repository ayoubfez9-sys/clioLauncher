import sys, os, time; sys.path.insert(0, '.')
import bpy
bpy.ops.wm.read_factory_settings(use_empty=True)
import build2 as C, cockpit as K
C.PLATE_STYLE = 'black'; C.CABIN_GAIN = 2.5; C.TRUNK_W = 14
OUT, W, H, SAMP, N = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
only = [int(x) for x in sys.argv[6].split(',')] if len(sys.argv) > 6 else None
os.makedirs(OUT, exist_ok=True)
M = C.materials()
# ciel de toit : la face intérieure du pavillon (dos de la tôle) devient un tissu gris clair
nt = M['body'].node_tree; out = nt.nodes['Material Output']; pb = C.P(M['body'])
geo = nt.nodes.new('ShaderNodeNewGeometry'); fab = nt.nodes.new('ShaderNodeBsdfDiffuse'); fab.inputs['Color'].default_value = (0.32, 0.32, 0.31, 1)
fab.inputs['Roughness'].default_value = 1.0; mx = nt.nodes.new('ShaderNodeMixShader')
nt.links.new(geo.outputs['Backfacing'], mx.inputs[0]); nt.links.new(pb.outputs[0], mx.inputs[1]); nt.links.new(fab.outputs[0], mx.inputs[2]); nt.links.new(mx.outputs[0], out.inputs['Surface'])
P = C.build(M); piv = C.setup_pivots(P); C.hub_caps(P, M); C.front_plate(M); C.steer(P)
sc = C.scene_setup(samples=SAMP, w=W, h=H); sc.view_settings.exposure = -0.9
sc.cycles.use_adaptive_sampling = True; sc.cycles.adaptive_threshold = 0.02; sc.cycles.adaptive_min_samples = 16
bpy.data.objects.remove(bpy.data.objects['shadow'])      # le plateau reçoit directement l'ombre
C.floor_disc()
cam = sc.camera
if cam is None:
    cd = bpy.data.cameras.new('cam'); cam = bpy.data.objects.new('cam', cd); sc.collection.objects.link(cam); sc.camera = cam
cam.data.clip_start = 0.01
for i in (only or range(N)):
    f = OUT + 'ck_%02d.png' % i
    if os.path.exists(f): continue
    t = time.time(); K.place(cam, i / (N - 1)); sc.render.filepath = f; bpy.ops.render.render(write_still=True); print('OK', i, round(time.time() - t), flush=True)
print('CKDONE', flush=True)
