package me.cortex.voxy.client.core.gl.shader;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlDebug;
import me.cortex.voxy.client.core.gl.GlTexture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;


//TODO: rewrite the entire shader builder system
public class AutoBindingShader extends Shader {

    private static final class BufferBinding {
        private final int target;
        private final int index;
        private final GlBuffer buffer;
        private final long offset;
        private final long size;

        public BufferBinding(int target, int index, GlBuffer buffer, long offset, long size) {
            this.target = target;
            this.index = index;
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
        }

        public int target() { return this.target; }
        public int index() { return this.index; }
        public GlBuffer buffer() { return this.buffer; }
        public long offset() { return this.offset; }
        public long size() { return this.size; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof BufferBinding)) return false;
            BufferBinding o = (BufferBinding) obj;
            return this.target == o.target && this.index == o.index && java.util.Objects.equals(this.buffer, o.buffer) && this.offset == o.offset && this.size == o.size;
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.target, this.index, this.buffer, this.offset, this.size); }

        @Override public String toString() { return "BufferBinding[target=" + this.target + ", index=" + this.index + ", buffer=" + this.buffer + ", offset=" + this.offset + ", size=" + this.size + "]"; }

    }
    private static final class TextureBinding {
        private final int unit;
        private final int sampler;
        private final GlTexture texture;

        public TextureBinding(int unit, int sampler, GlTexture texture) {
            this.unit = unit;
            this.sampler = sampler;
            this.texture = texture;
        }

        public int unit() { return this.unit; }
        public int sampler() { return this.sampler; }
        public GlTexture texture() { return this.texture; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TextureBinding)) return false;
            TextureBinding o = (TextureBinding) obj;
            return this.unit == o.unit && this.sampler == o.sampler && java.util.Objects.equals(this.texture, o.texture);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.unit, this.sampler, this.texture); }

        @Override public String toString() { return "TextureBinding[unit=" + this.unit + ", sampler=" + this.sampler + ", texture=" + this.texture + "]"; }

    }

    private final Map<String, String> defines;
    private final List<BufferBinding> bindings = new ArrayList<>();
    private final List<TextureBinding> textureBindings = new ArrayList<>();

    private boolean rebuild = true;

    AutoBindingShader(Shader.Builder<AutoBindingShader> builder, int program) {
        super(program);
        this.defines = builder.defines;
    }

    public AutoBindingShader name(String name) {
        return GlDebug.name(name, this);
    }

    public AutoBindingShader ssboIf(String define, GlBuffer buffer) {
        if (this.defines.containsKey(define)) {
            return this.ssbo(define, buffer);
        }
        return this;
    }

    public AutoBindingShader ssbo(int index, GlBuffer binding) {
        return this.ssbo(index, binding, 0);
    }

    public AutoBindingShader ssbo(String define, GlBuffer binding) {
        return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0);
    }

    public AutoBindingShader ssbo(int index, GlBuffer buffer, long offset) {
        this.insertOrReplaceBinding(new BufferBinding(GL_SHADER_STORAGE_BUFFER, index, buffer, offset, -1));
        return this;
    }


    public AutoBindingShader ubo(String define, GlBuffer buffer) {
        return this.ubo(Integer.parseInt(this.defines.get(define)), buffer);
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer) {
        return this.ubo(index, buffer, 0);
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer, long offset) {
        this.insertOrReplaceBinding(new BufferBinding(GL_UNIFORM_BUFFER, index, buffer, offset, -1));
        return this;
    }

    private void insertOrReplaceBinding(BufferBinding binding) {
        this.rebuild = true;

        //Check if there is already a binding at the index with the target, if so, replace it
        for (int i = 0; i < this.bindings.size(); i++) {
            var entry = this.bindings.get(i);
            if (entry.target == binding.target && entry.index == binding.index) {
                this.bindings.set(i, binding);
                return;
            }
        }

        //Else add the new binding
        this.bindings.add(binding);
    }

    public AutoBindingShader texture(String define, GlTexture texture) {
        return this.texture(define, -1, texture);
    }

    public AutoBindingShader texture(String define, int sampler, GlTexture texture) {
        return this.texture(Integer.parseInt(this.defines.get(define)), sampler, texture);
    }

    public AutoBindingShader texture(int unit, int sampler, GlTexture texture) {
        this.rebuild = true;

        for (int i = 0; i < this.textureBindings.size(); i++) {
            var entry = this.textureBindings.get(i);
            if (entry.unit == unit) {
                this.textureBindings.set(i, new TextureBinding(unit, sampler, texture));
                return this;
            }
        }

        this.textureBindings.add(new TextureBinding(unit, sampler, texture));
        return this;
    }

    @Override
    public void bind() {
        super.bind();
        //TODO: replace with multibind and use the invalidate flag
        /*
        glBindSamplers();
        glBindTextures();
        glBindBuffersBase();
        glBindBuffersRange();
         */
        if (!this.bindings.isEmpty()) {
            for (var binding : this.bindings) {
                binding.buffer.assertNotFreed();
                if (binding.offset == 0 && binding.size == -1) {
                    glBindBufferBase(binding.target, binding.index, binding.buffer.id);
                } else {
                    glBindBufferRange(binding.target, binding.index, binding.buffer.id, binding.offset, binding.size);
                }
            }
        }
        if (!this.textureBindings.isEmpty()) {
            for (var binding : this.textureBindings) {
                if (binding.texture != null) {
                    binding.texture.assertNotFreed();
                    glBindTextureUnit(binding.unit, binding.texture.id);
                }
                if (binding.sampler != -1) {
                    glBindSampler(binding.unit, binding.sampler);
                }
            }
        }
    }
}
