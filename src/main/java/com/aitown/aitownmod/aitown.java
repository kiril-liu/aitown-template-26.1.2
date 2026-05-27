package com.aitown.aitownmod;

import org.slf4j.Logger;

// 记得保留你文件最上面原有的 package 声明！比如：package com.yourname.aitown;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(aitown.MODID)
public class aitown {
    // 模组的 ID
    public static final String MODID = "aitown";
    public static final Logger LOGGER = LogUtils.getLogger();
    // 1. 专门用来注册 物品(Items) 的注册器
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // 2. 专门用来注册 创造模式选项卡(CreativeModeTabs) 的注册器
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);


    public static final DeferredItem<TownSystemChipItem> TOWN_SYSTEM_CHIP = ITEMS.registerItem(
            "town_system_chip",
            properties -> new TownSystemChipItem(properties.stacksTo(1))
    );

    // =========================================================
// 【核心修改 2】注册我们自己的创造模式选项卡，并把芯片放进去
// =========================================================
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AITOWN_TAB = CREATIVE_MODE_TABS.register("aitown_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.aitown")) // 选项卡的内部语言名称
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)       // 把我们的选项卡放在“刷怪蛋”前面
            .displayItems((parameters, output) -> {

                output.accept(TOWN_SYSTEM_CHIP.get());
            }).build());

    // 构造函数：Mod 加载时最先运行的代码
    public aitown(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // 将我们的物品和选项卡注册器绑定到模组事件总线
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);


        NeoForge.EVENT_BUS.register(this);

        // 如果你有 Config 类的话保留这行，如果没有会飘红，直接删掉或者注释掉即可
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("AI Town Mod Setup Complete!");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("AI Town Server Starting!");
    }
}