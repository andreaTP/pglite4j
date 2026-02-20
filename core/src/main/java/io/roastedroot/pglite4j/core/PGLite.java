package io.roastedroot.pglite4j.core;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;

@WasmModuleInterface(WasmResource.absoluteFile)
public final class PGLite implements AutoCloseable {
    private final Instance instance;
    private final WasiPreview1 wasi;
    private final PGLite_ModuleExports exports;

    private PGLite() {
        var wasiOpts = WasiOptions.builder().inheritSystem().build();
        this.wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
        this.instance =
                Instance.builder(PGLiteModule.load())
                        .withImportValues(imports)
                        .withMachineFactory(PGLiteModule::create)
                        .build();
        this.exports = new PGLite_ModuleExports(this.instance);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        if (wasi != null) {
            wasi.close();
        }
    }

    public static final class Builder {
        private Builder() {}

        public PGLite build() {
            return new PGLite();
        }
    }
}
