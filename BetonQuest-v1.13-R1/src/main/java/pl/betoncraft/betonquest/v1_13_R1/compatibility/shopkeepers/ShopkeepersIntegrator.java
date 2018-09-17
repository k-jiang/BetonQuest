/**
 * BetonQuest - advanced quests for Bukkit
 * Copyright (C) 2016  Jakub "Co0sh" Sapalski
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.betoncraft.betonquest.v1_13_R1.compatibility.shopkeepers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.betoncraft.betonquest.BetonQuest;
import pl.betoncraft.betonquest.compatibility.Integrator;
import pl.betoncraft.betonquest.compatibility.UnsupportedVersionException;


public class ShopkeepersIntegrator implements Integrator {
    
    private BetonQuest plugin;
    
    public ShopkeepersIntegrator() {
        plugin = BetonQuest.getInstance();
    }

    @Override
    public void hook() throws Exception {
        Plugin shopkeepers = Bukkit.getPluginManager().getPlugin("Shopkeepers");
        if (shopkeepers.getDescription().getVersion().startsWith("1."))
            throw new UnsupportedVersionException(shopkeepers, "2.2.0");
        plugin.registerEvents("shopkeeper", OpenShopEvent.class);
        plugin.registerConditions("shopamount", HavingShopCondition.class);
    }

    @Override
    public void reload() {

    }

    @Override
    public void close() {

    }

}