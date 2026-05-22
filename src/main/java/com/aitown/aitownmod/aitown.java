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
import org.slf4j.Logger;

@Mod(aitown.MODID)
public class aitown {
    // 模组的 ID
    public static final String MODID = "aitown";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 1. 专门用来注册 物品(Items) 的注册器
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // 2. 专门用来注册 创造模式选项卡(CreativeModeTabs) 的注册器
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // =========================================================
    // 【核心修改 1】注册“村民建筑芯片” (villager_chip)
    // =========================================================
    //public static final DeferredItem<Item> VILLAGER_CHIP = ITEMS.registerSimpleItem("villager_chip");
    // 我们不再用原版的 Item，而是用刚刚写好的 VillagerChipItem
    //public static final DeferredItem<Item> VILLAGER_CHIP = ITEMS.register("villager_chip",
    //        () -> new VillagerChipItem(new Item.Properties().stacksTo(64))); // 顺便设置最大堆叠为64
    // 改用 registerItem，它会把带好 ID 的 properties 传给我们
    public static final DeferredItem<VillagerChipItem> VILLAGER_CHIP = ITEMS.registerItem(
            "villager_chip",
            properties -> new VillagerChipItem(properties.stacksTo(64))
    );

    // 注册“伐木工芯片” (lumberjack_chip)
    public static final DeferredItem<LumberjackChipItem> LUMBERJACK_CHIP = ITEMS.registerItem(
            "lumberjack_chip",
            properties -> new LumberjackChipItem(properties.stacksTo(64))
    );

    // =========================================================
    // 【核心修改 2】注册我们自己的创造模式选项卡，并把芯片放进去
    // =========================================================
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AITOWN_TAB = CREATIVE_MODE_TABS.register("aitown_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.aitown")) // 选项卡的内部语言名称
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)       // 把我们的选项卡放在“刷怪蛋”前面
            .icon(() -> VILLAGER_CHIP.get().getDefaultInstance()) // 选项卡的图标就用我们的建筑芯片！
            .displayItems((parameters, output) -> {
                output.accept(VILLAGER_CHIP.get());            // 把芯片加进这个选项卡里
                output.accept(LUMBERJACK_CHIP.get());  // 【新增】把伐木芯片也放进选项卡
            }).build());

    // 构造函数：Mod 加载时最先运行的代码
    public aitown(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // 将我们的物品和选项卡注册器绑定到模组事件总线
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // 【新增这一行】注册我们的专属背包 UI！
        //ModMenus.MENUS.register(modEventBus);

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
