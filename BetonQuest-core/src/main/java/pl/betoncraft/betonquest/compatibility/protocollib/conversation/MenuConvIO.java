/*
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

package pl.betoncraft.betonquest.compatibility.protocollib.conversation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import pl.betoncraft.betonquest.BetonQuest;
import pl.betoncraft.betonquest.compatibility.protocollib.wrappers.WrapperPlayClientSteerVehicle;
import pl.betoncraft.betonquest.compatibility.protocollib.wrappers.WrapperPlayServerEntityDestroy;
import pl.betoncraft.betonquest.compatibility.protocollib.wrappers.WrapperPlayServerMount;
import pl.betoncraft.betonquest.compatibility.protocollib.wrappers.WrapperPlayServerSpawnEntityLiving;
import pl.betoncraft.betonquest.config.Config;
import pl.betoncraft.betonquest.config.ConfigPackage;
import pl.betoncraft.betonquest.conversation.Conversation;
import pl.betoncraft.betonquest.conversation.ConversationColors;
import pl.betoncraft.betonquest.conversation.ConversationIO;
import pl.betoncraft.betonquest.utils.LocalChatPaginator;
import pl.betoncraft.betonquest.utils.PlayerConverter;
import pl.betoncraft.betonquest.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MenuConvIO implements Listener, ConversationIO {

    protected final Conversation conv;
    protected final String name;
    protected final Player player;
    protected final HashMap<String, ChatColor[]> colors;
    protected List<String> options;
    protected int oldSelectedOption = 0;
    protected int selectedOption = 0;
    protected String npcText;
    protected String npcName;
    protected boolean ended = false;
    protected PacketAdapter packetAdapter;
    protected BukkitRunnable displayRunnable;
    protected boolean debounce = false;
    protected String displayOutput;
    // Configuration
    protected String configNpcWrap = "&l &r".replace('&', '§');
    protected String configNpcText = "&l &r&f{1}".replace('&', '§');
    protected String configNpcTextReset = "&f".replace('&', '§');
    protected String configOptionWrap = "&l &l &l &l &r".replace('&', '§');
    protected String configOptionText = "&l &l &l &l &r&8[ &b{1}&8 ]".replace('&', '§');
    protected String configOptionTextReset = "&b".replace('&', '§');
    protected String configOptionSelected = "&l &r &r&7»&r &8[ &f&n{1}&8 ]".replace('&', '§');
    protected String configOptionSelectedReset = "&f".replace('&', '§');
    private WrapperPlayServerSpawnEntityLiving stand = null;

    public MenuConvIO(Conversation conv, String playerID) {
        this.options = new ArrayList<>();
        this.conv = conv;
        this.player = PlayerConverter.getPlayer(playerID);
        this.name = player.getName();
        this.colors = ConversationColors.getColors();

        // Load Configuration from custom.yml with some sane defaults, loading our current package last
        for (ConfigPackage pack : Stream.concat(
                Config.getPackages().values().stream()
                        .filter(p -> p != conv.getPackage()),
                Stream.of(conv.getPackage()))
                .collect(Collectors.toList())) {
            ConfigurationSection section = pack.getCustom().getConfig().getConfigurationSection("menu_conv_io");
            if (section == null) {
                continue;
            }

            configNpcWrap = section.getString("npc_wrap", configNpcWrap).replace('&', '§');
            configNpcText = section.getString("npc_text", configNpcText).replace('&', '§');
            configNpcTextReset = section.getString("npc_text_reset", configNpcTextReset).replace('&', '§');
            configOptionWrap = section.getString("option_wrap", configOptionWrap).replace('&', '§');
            configOptionText = section.getString("option_text", configOptionText).replace('&', '§');
            configOptionTextReset = section.getString("option_text_reset", configOptionTextReset).replace('&', '§');
            configOptionSelected = section.getString("option_selected", configOptionSelected).replace('&', '§');
            configOptionSelectedReset = section.getString("option_selected_reset", configOptionSelectedReset).replace('&', '§');
        }

        // Create something painful looking for the player to sit on and make it invisible.
        stand = new WrapperPlayServerSpawnEntityLiving();
        stand.setType(EntityType.ARMOR_STAND);
        stand.setUniqueId(UUID.randomUUID());
        stand.setX(player.getLocation().getX());
        stand.setY(player.getLocation().getY() - 1.1);
        stand.setZ(player.getLocation().getZ());

        WrappedDataWatcher.Serializer byteSerializer = WrappedDataWatcher.Registry.get(Byte.class);
        WrappedDataWatcher wdw = new WrappedDataWatcher();
        WrappedDataWatcher.WrappedDataWatcherObject invisible = new WrappedDataWatcher.WrappedDataWatcherObject(0, byteSerializer);
        wdw.setObject(invisible, (byte) 0x20);

        stand.setMetadata(wdw);

        stand.sendPacket(player);

        // Mount the player to it
        WrapperPlayServerMount mount = new WrapperPlayServerMount();
        mount.setEntityID(stand.getEntityID());
        mount.setPassengerIds(new int[]{player.getEntityId()});
        mount.sendPacket(player);

        // Display Actionbar to hide the dismount message
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new ComponentBuilder(npcName).color(net.md_5.bungee.api.ChatColor.YELLOW).create());

        // Intercept Packets

        packetAdapter = new PacketAdapter(BetonQuest.getInstance().getJavaPlugin(), ListenerPriority.HIGHEST, PacketType.Play.Client.STEER_VEHICLE) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPlayer() != player || options.size() == 0) {
                    return;
                }

                if (event.getPacketType().equals(PacketType.Play.Client.STEER_VEHICLE)) {
                    WrapperPlayClientSteerVehicle steerEvent = new WrapperPlayClientSteerVehicle(event.getPacket());

                    // Check jump
                    if (steerEvent.isJump() && !debounce) {
                        conv.passPlayerAnswer(selectedOption + 1);
                        debounce = true;
                    } else if (steerEvent.getForward() < 0 && selectedOption < options.size() - 1 && !debounce) {
                        oldSelectedOption = selectedOption;
                        selectedOption++;
                        debounce = true;
                        updateDisplay();
                    } else if (steerEvent.getForward() > 0 && selectedOption > 0 && !debounce) {
                        oldSelectedOption = selectedOption;
                        selectedOption--;
                        debounce = true;
                        updateDisplay();
                    } else if (Math.abs(steerEvent.getForward()) < 0.01) {
                        debounce = false;
                    }

                    event.setCancelled(true);
                }
            }
        };

        ProtocolLibrary.getProtocolManager().addPacketListener(packetAdapter);

        Bukkit.getPluginManager().registerEvents(this, BetonQuest.getInstance().getJavaPlugin());
    }

    /**
     * Set the text of response chosen by the NPC. Should be called once per
     * conversation cycle.
     *
     * @param npcName  the name of the NPC
     * @param response the text the NPC chose
     */
    @Override
    public void setNpcResponse(String npcName, String response) {
        this.npcName = npcName;
        this.npcText = response;
    }

    /**
     * Adds the text of the player option. Should be called for each option in a
     * conversation cycle.
     *
     * @param option the text of an option
     */
    @Override
    public void addPlayerOption(String option) {
        options.add(option);
    }

    /**
     * Displays all data to the player. Should be called after setting all
     * options.
     */
    @Override
    public void display() {
        if (npcText == null && options.isEmpty()) {
            end();
            return;
        }

        updateDisplay();

        // Update the Display
        displayRunnable = new BukkitRunnable() {

            @Override
            public void run() {
                showDisplay();

                if (ended) {
                    this.cancel();
                }
            }
        };

        displayRunnable.runTaskTimerAsynchronously(BetonQuest.getInstance().getJavaPlugin(), 0, 40);
    }

    protected void showDisplay() {
        if (displayOutput != null) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new ComponentBuilder(npcName).color(net.md_5.bungee.api.ChatColor.YELLOW).create());
            player.spigot().sendMessage(TextComponent.fromLegacyText(displayOutput));
        }
    }

    protected void updateDisplay() {
        if (npcText == null) {
            displayOutput = null;
            return;
        }

        // NPC Text
        String msgNpcText = configNpcText.replace("{1}", npcText);

        List<String> npcLines = Arrays.stream(LocalChatPaginator.wordWrap(
                Utils.replaceReset(msgNpcText, configNpcTextReset), 60))
                .collect(Collectors.toList());

        // Provide for as many options as we can fit but if there is lots of npcLines we will reduce this as necessary down to a minimum of 1.
        int linesAvailable = Math.max(1, 10 - npcLines.size());

        // Add space for the up/down arrows
        if (options.size() > 0) {
            linesAvailable = Math.max(1, linesAvailable - 2);
        }

        // Displaying options is tricky. We need to deal with if the selection has moved, multi-line options and less space for all options due to npc text
        List<String> optionsSelected = new ArrayList<>();
        int currentOption = selectedOption;
        int currentDirection = selectedOption != oldSelectedOption ? selectedOption - oldSelectedOption : 1;
        int topOption = options.size();
        for (int i = 0; i < options.size() && linesAvailable > (i < 2 ? 0 : 1); i++) {
            int optionIndex = currentOption + (i * currentDirection);
            if (optionIndex > options.size() - 1) {
                optionIndex = currentOption - (optionIndex - (options.size() - 1));
                currentDirection = -currentDirection;
                if (optionIndex < 0) {
                    break;
                }
            } else if (optionIndex < 0) {
                optionIndex = currentOption + (0 - optionIndex);
                if (optionIndex > options.size() - 1) {
                    break;
                }
                currentDirection = -currentDirection;
            }

            if (topOption > optionIndex) {
                topOption = optionIndex;
            }

            String optionText;

            if (i == 0) {
                optionText = configOptionSelected.replace("{1}", options.get(optionIndex));
            } else {
                optionText = configOptionText.replace("{1}", options.get(optionIndex));
            }

            List<String> optionLines = Arrays.stream(LocalChatPaginator.wordWrap(
                    Utils.replaceReset(optionText, i == 0 ? configOptionSelectedReset : configOptionTextReset),
                    60))
                    .collect(Collectors.toList());

            if (linesAvailable < optionLines.size()) {
                break;
            }

            linesAvailable -= optionLines.size();

            if (currentDirection > 0) {
                optionsSelected.add(ChatColor.RESET + String.join("\n" + configOptionWrap, optionLines));
            } else {
                optionsSelected.add(0, ChatColor.RESET + String.join("\n" + configOptionWrap, optionLines));
            }

            currentOption = optionIndex;
            currentDirection = -currentDirection;
        }

        // Build the displayOutput
        StringBuilder displayBuilder = new StringBuilder();

        if (options.size() > 0) {

            // We aim to try have a blank line at the top. It looks better
            if (linesAvailable > 0) {
                displayBuilder.append(" \n");
                linesAvailable--;
            }

            displayBuilder.append(String.join("\n" + configNpcWrap, npcLines)).append("\n");

            // Put clear lines between NPC text and Options
            for (int i = 0; i < linesAvailable; i++) {
                displayBuilder.append(" \n");
            }

            if (topOption > 0) {
                displayBuilder
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.WHITE).append("↑\n");
            } else {
                displayBuilder.append(" \n");
            }

            displayBuilder.append(String.join("\n", optionsSelected)).append("\n");

            if (topOption + optionsSelected.size() < options.size()) {
                displayBuilder
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.BOLD).append(" ")
                        .append(ChatColor.WHITE).append("↓");
            }
        } else {
            // Put clear lines above NPC Text
            for (int i = 0; i < 90 + linesAvailable - 1; i++) {
                displayBuilder.append(" \n");
            }

            displayBuilder.append(String.join("\n" + configNpcWrap, npcLines)).append("\n");

            if (linesAvailable > 0) {
                displayBuilder.append(" \n");
            }
        }

        displayOutput = displayBuilder.toString();

        showDisplay();
    }

    /**
     * Clears the data. Should be called before the cycle begins to ensure
     * nothing is left from previous one.
     */
    @Override
    public void clear() {
        if (displayRunnable != null) {
            displayRunnable.cancel();
            displayRunnable = null;
        }

        selectedOption = 0;
        oldSelectedOption = 0;

        options.clear();
        npcText = null;

//        // Clear conversation
//        for (int i = 0; i < 100; i++) {
//            player.sendMessage(" \n");
//        }
    }

    /**
     * Ends the work of this conversation IO. Should be called when the
     * conversation ends.
     */
    @Override
    public void end() {
        ended = true;

        // Stop Listening for Packets
        ProtocolLibrary.getProtocolManager().removePacketListener(packetAdapter);

        // Destroy Stand
        WrapperPlayServerEntityDestroy destroyPacket = new WrapperPlayServerEntityDestroy();
        destroyPacket.setEntities(new int[]{stand.getEntityID()});
        destroyPacket.sendPacket(player);

        // Stop updating display
        if (displayRunnable != null) {
            displayRunnable.cancel();
            displayRunnable = null;
        }

        HandlerList.unregisterAll(this);
    }

    /**
     * @return if this conversationIO should send messages to the player when the conversation starts and ends
     */
    @Override
    public boolean printMessages() {
        return false;
    }

    /**
     * Send message through ConversationIO
     *
     * @param message
     */
    @Override
    public void print(String message) {
        if (message != null && message.length() > 0) {
            player.sendMessage(message);
        }
    }

    @EventHandler
    public void PlayerMoveEvent(PlayerMoveEvent event) {
        if (event.getPlayer() != player) {
            return;
        }

        // If the player has moved away somehow we cancel everything
        if (Math.abs(event.getFrom().getX() - event.getTo().getX()) + Math.abs(event.getFrom().getY() - event.getTo().getY()) + Math.abs(event.getFrom().getZ() - event.getTo().getZ()) > 3) {
            conv.endConversation();
        }
    }
}
