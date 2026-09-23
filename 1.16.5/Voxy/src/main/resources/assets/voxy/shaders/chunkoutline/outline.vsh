#version 460

#import <voxy:util/depthutils.glsl>

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 cameraBlockPos;
    vec4 negInnerBlock;
    float boundDepthBias;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    //Every entry in this buffer was handed to us by Sodium's own section collector, so it is
    //already exactly the set of sections Sodium is drawing this frame. Re-deriving "is it in
    //range?" here with an approximate cylinder can only ever drop a box that should be there,
    //and the sections it gets wrong are the ones on the render-distance boundary -- precisely
    //where the LoD has to line up with real terrain.
    //
    //Worse, it is keyed off cameraBlockPos, which ticks over once per block walked, so boundary
    //sections flip in and out of the test as you move: their mask box blinks, and the LoD blinks
    //over the real chunk with it. Sections that land on the wrong side of it and stay there show
    //up as a fixed seam instead (most visible on water, where LoD and real geometry are coplanar).
    //
    //Dropping the test costs almost nothing: in the non-degenerate case it was already true for
    //nearly every box, since the store only ever holds in-range sections to begin with.
    return true;

    /* was, tuned against Sodium 0.9.x's culling and mismatched on 0.8.13:
    vec3 corner = vec3(mix(mix(ivec3(0), icorner-1, greaterThan(icorner-1, ivec3(0))), icorner+17, lessThan(icorner+17, ivec3(0))))-negInnerBlock.xyz;
    bool visible = (corner.x*corner.x + corner.z*corner.z) < (negInnerBlock.w*negInnerBlock.w);
    visible = visible && abs(corner.y) < negInnerBlock.w;
    return visible;
    */
}

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);

    ivec3 origin = unpackPos(chunkPos[id])*16;
    origin -= cameraBlockPos.xyz;

    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0f, -100.0f, -100.0f, 0.0f);
        return;
    }

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*16;
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    //cubeCornerI.y = cubeCornerI.y*1024-512;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);

    //Nudge the mask towards the camera by a couple of depth-buffer ulps, no more.
    //This offset is in clip space and is NOT divided by w, so it works out to a fixed
    //fraction of the view distance. The old 0.0005 was ~0.5% of it -- about a block at
    //200 blocks -- which left a slab of LoD in front of the mask's far face that never
    //got discarded. On a grazing surface (water) one block of depth covers a wide band
    //of pixels, which is the line at the real-chunk/LoD border.
    gl_Position.z += CLOSER_SIGN*boundDepthBias;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif
}



//Undefine depth stuff
#import <voxy:util/depthutils.glsl>
