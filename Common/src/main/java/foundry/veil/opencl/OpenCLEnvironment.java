package foundry.veil.opencl;

import com.mojang.logging.LogUtils;
import foundry.veil.Veil;
import foundry.veil.opencl.event.CLEventDispatcher;
import foundry.veil.opencl.event.CLLegacyEventDispatcher;
import foundry.veil.opencl.event.CLNativeEventDispatcher;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CLContextCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL20.clCreateCommandQueueWithProperties;

/**
 * An OpenCL runtime environment on a specific device.
 *
 * @author Ocelot
 */
public class OpenCLEnvironment implements NativeResource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter SHADERS = new FileToIdConverter("pinwheel/compute", ".cl");

    private final VeilOpenCL.DeviceInfo device;
    private final CLContextCallback errorCallback;
    private final long context;
    private final long commandQueue;
    private final Map<ResourceLocation, ProgramData> programs;
    private final CLEventDispatcher eventDispatcher;

    public OpenCLEnvironment(VeilOpenCL.DeviceInfo deviceInfo) throws OpenCLException {
        this.device = deviceInfo;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ctxProps = stack.mallocPointer(3);
            ctxProps
                    .put(0, CL_CONTEXT_PLATFORM)
                    .put(1, deviceInfo.platform())
                    .put(2, 0);

            long device = deviceInfo.id();

            this.errorCallback = CLContextCallback.create((errinfo, private_info, cb, user_data) -> {
                Veil.LOGGER.error("[LWJGL] cl_context_callback");
                Veil.LOGGER.error("\tInfo: " + MemoryUtil.memUTF8(errinfo));
            });
            IntBuffer errcode_ret = stack.callocInt(1);

            try {
                this.context = clCreateContext(ctxProps, device, this.errorCallback, MemoryUtil.NULL, errcode_ret);
                VeilOpenCL.checkCLError(errcode_ret);

                this.commandQueue = clCreateCommandQueueWithProperties(this.context, device, null, errcode_ret);
                VeilOpenCL.checkCLError(errcode_ret);

                if (this.commandQueue == MemoryUtil.NULL) {
                    throw new IllegalStateException("Failed to create OpenCL queue");
                }
            } catch (Exception e) {
                this.free();
                throw e;
            }
        }

        this.programs = new HashMap<>();
        this.eventDispatcher = deviceInfo.capabilities().clSetEventCallback != 0 ? new CLNativeEventDispatcher() : new CLLegacyEventDispatcher();
    }

    /**
     * Loads the specified source code under the specified name.
     *
     * @param name   The name of the shader to load
     * @param source The source code to compile
     */
    public void loadProgram(ResourceLocation name, CharSequence source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long program = 0;
            IntBuffer errcode_ret = stack.callocInt(1);
            try {
                program = clCreateProgramWithSource(this.context, source, errcode_ret);
                VeilOpenCL.checkCLError(errcode_ret);

                long device = this.device.id();
                int programStatus = clBuildProgram(program, device, "", null, 0);
                if (programStatus != CL_SUCCESS) {
                    System.err.println(VeilOpenCL.getProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG));
                    throw new OpenCLException("Failed to compile program", programStatus);
                }

                ProgramData oldProgram = this.programs.put(name, new ProgramData(program));
                if (oldProgram != null) {
                    LOGGER.info("Deleting old program: {}", name);
                    oldProgram.free();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load program from source: {}", name, e);
                if (program != 0) {
                    clReleaseProgram(program);
                }
            }
        }
    }

    /**
     * Loads the specified program binary from file.
     *
     * @param provider The provider for files
     * @param name     The name of the shader file
     * @throws IOException If any errors occurs
     */
    public void loadProgram(ResourceLocation name, ResourceProvider provider) throws IOException {
        Resource resource = provider.getResourceOrThrow(SHADERS.idToFile(name));
        try (InputStream stream = resource.open()) {
            this.loadProgram(name, IOUtils.toString(stream, StandardCharsets.UTF_8));
        }
    }

    /**
     * Retrieves a kernel from the specified shader program.
     *
     * @param program    The name of the program to get the kernel from
     * @param kernelName The name of the kernel
     * @return The kernel found or <code>null</code> if there was an error creating the kernel
     */
    public @Nullable CLKernel getKernel(ResourceLocation program, String kernelName) {
        ProgramData programData = this.programs.get(program);
        if (programData == null || programData.invalidKernels.contains(kernelName)) {
            return null;
        }

        CLKernel kernel = programData.kernels.get(kernelName);
        if (kernel == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer errcode_ret = stack.callocInt(1);
                long kernelId = clCreateKernel(programData.id, kernelName, errcode_ret);
                if (errcode_ret.get(0) == CL_INVALID_KERNEL_NAME) {
                    throw new OpenCLException("Failed to find kernel: " + kernelName, errcode_ret.get(0));
                }
                VeilOpenCL.checkCLError(errcode_ret);

                kernel = new CLKernel(this, kernelId);
                programData.kernels.put(kernelName, kernel);
            } catch (OpenCLException e) {
                LOGGER.error("Failed to create kernel: {}", kernelName, e);
                programData.invalidKernels.add(kernelName);
            }
        }
        return kernel;
    }

    /**
     * Blocks until all CL commands have completed.
     *
     * @throws OpenCLException If any error occurs while trying to block
     */
    public void finish() throws OpenCLException {
        VeilOpenCL.checkCLError(clFinish(this.commandQueue));
    }

    @Override
    public void free() {
        this.errorCallback.free();
        if (this.commandQueue != 0) {
            clReleaseCommandQueue(this.commandQueue);
        }
        if (this.context != 0) {
            clReleaseContext(this.context);
        }
        this.programs.values().forEach(NativeResource::free);
        this.programs.clear();

        try {
            this.eventDispatcher.close();
        } catch (InterruptedException e) {
            LOGGER.error("Failed to stop event dispatcher", e);
        }
    }

    /**
     * @return The device this environment is in
     */
    public VeilOpenCL.DeviceInfo getDevice() {
        return this.device;
    }

    /**
     * @return The dispatcher for event callbacks
     */
    public CLEventDispatcher getEventDispatcher() {
        return this.eventDispatcher;
    }

    /**
     * @return The pointer to the OpenCL context
     */
    public long getContext() {
        return this.context;
    }

    /**
     * @return The pointer to the OpenCL queue
     */
    public long getCommandQueue() {
        return this.commandQueue;
    }

    private record ProgramData(long id,
                               Map<String, CLKernel> kernels,
                               Set<String> invalidKernels) implements NativeResource {

        private ProgramData(long id) {
            this(id, new HashMap<>(), new HashSet<>());
        }

        @Override
        public void free() {
            clReleaseProgram(this.id);
            this.kernels.values().forEach(NativeResource::free);
            this.kernels.clear();
            this.invalidKernels.clear();
        }
    }
}
