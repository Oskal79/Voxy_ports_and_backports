package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        //Note this is where will choose/create e.g. IrisRenderPipeline or normal pipeline
        AbstractRenderPipeline pipeline = null;
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            pipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        if (pipeline == null) {
            pipeline = new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        return pipeline;
    }

    private static AbstractRenderPipeline createIrisPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline dpipe = me.cortex.voxy.client.core.util.OculusPipelineBridge.getDeferredPipeline();
        if (dpipe == null) {
            return null;
        }
        me.cortex.voxy.client.iris.IrisShaderPatch patch = me.cortex.voxy.client.core.util.OculusPipelineBridge.findActiveShaderPatch();
        if (patch == null) {
            Logger.info("Active shaderpack has no Voxy support (no voxy.json), skipping Iris pipeline");
            return null;
        }
        Logger.info("Creating Voxy Oculus render pipeline for active shaderpack");
        try {
            me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData data = me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData.buildPipeline(dpipe, patch);
            return new IrisVoxyRenderPipeline(properties, data, nodeManager, nodeCleaner, traversal, frexSupplier);
        } catch (Exception e) {
            Logger.error("Failed to create Iris Voxy render pipeline", e);
            return null;
        }
    }
}
