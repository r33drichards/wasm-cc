package cc.wasmcc.mod;

import net.fabricmc.api.ModInitializer;

public final class WasmCcMod implements ModInitializer {
    @Override
    public void onInitialize() {
        WasmApiRegistration.register();
    }
}
