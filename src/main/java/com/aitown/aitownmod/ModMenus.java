package com.aitown.aitownmod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, aitown.MODID);

    public static final Supplier<MenuType<VillagerInventoryMenu>> VILLAGER_INVENTORY_MENU = MENUS.register(
            "villager_inventory_menu",
            () -> IMenuTypeExtension.create(VillagerInventoryMenu::new)
    );
}