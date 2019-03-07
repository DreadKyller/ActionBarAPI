package com.connorlinfoot.actionbarapi;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;


public class ActionBarAPI extends JavaPlugin implements Listener {
    private static Plugin plugin;
    private static boolean useOldMethods = false;

    public void onEnable() {
        plugin = this;
        getConfig().options().copyDefaults(true);
        saveConfig();

        CLUpdate clUpdate = new CLUpdate(this);

        Server server = getServer();
        ConsoleCommandSender console = server.getConsoleSender();

        String nmsver = Bukkit.getServer().getClass().getPackage().getName();
        nmsver = nmsver.substring(nmsver.lastIndexOf(".") + 1);

        if (nmsver.equalsIgnoreCase("v1_8_R1") || nmsver.startsWith("v1_7_")) { // Not sure if 1_7 works for the protocol hack?
            useOldMethods = true;
        }

        console.sendMessage(ChatColor.AQUA + getDescription().getName() + " V" + getDescription().getVersion() + " has been enabled!");
        Bukkit.getPluginManager().registerEvents(clUpdate, this);

        // Gets and stored the Classes, Methods, Constructors and Objects that are used later on but do not change
        // This is to avoid calling very intensive methods every time the action bar is sent.
        ActionBarAPI.setupReflection(nmsver);
    }

    private static Class<?> craftPlayerClass, packetClass, chatSerializerComponentClass, iChatBaseComponentClass;
    private static Constructor<?> constPacketPlayOutChat, constChatComponent;
    private static Method newChatBaseComponent;
    private static Object ChatMessageType_GAME_INFO;

    private static boolean reflectionSetup = false;
    private static boolean useByteConstructor = false;

    /**
     * Gathers and stores the necessary reflection results for use in sending the packets.
     *
     * Reflection is not cheap and most operations don't need to be done multiple times.
     * Get once, use forever.
     *
     * @param nmsver The version string of the current NMS package.
     */
    private static void setupReflection (String nmsver) {
        try {
            craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + nmsver + ".entity.CraftPlayer");
            Class<?> packetPlayOutChatClass = Class.forName("net.minecraft.server." + nmsver + ".PacketPlayOutChat");
            packetClass = Class.forName("net.minecraft.server." + nmsver + ".Packet");
            if (useOldMethods) {
                chatSerializerComponentClass = Class.forName("net.minecraft.server." + nmsver + ".ChatSerializer");
                iChatBaseComponentClass = Class.forName("net.minecraft.server." + nmsver + ".IChatBaseComponent");
                constPacketPlayOutChat = packetPlayOutChatClass.getConstructor(iChatBaseComponentClass, byte.class);
                newChatBaseComponent = findMethod(chatSerializerComponentClass, "a", String.class);
            } else {
                chatSerializerComponentClass = Class.forName("net.minecraft.server." + nmsver + ".ChatComponentText");
                iChatBaseComponentClass = Class.forName("net.minecraft.server." + nmsver + ".IChatBaseComponent");
                Class<?> chatMessageTypeClass = Class.forName("net.minecraft.server." + nmsver + ".ChatMessageType");
                Object[] chatMessageTypes = chatMessageTypeClass.getEnumConstants();
                for (Object obj : chatMessageTypes) {
                    if (obj.toString().equals("GAME_INFO")) {
                        ChatMessageType_GAME_INFO = obj;
                    }
                }
                try {
                    constPacketPlayOutChat = packetPlayOutChatClass.getConstructor(iChatBaseComponentClass, chatMessageTypeClass);
                } catch (NoSuchMethodException e) {
                    constPacketPlayOutChat = packetPlayOutChatClass.getConstructor(iChatBaseComponentClass, byte.class);
                    useByteConstructor = true;
                }
                constChatComponent = chatSerializerComponentClass.getConstructor(String.class);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            return;
        }
        reflectionSetup = true;
    }

    /**
     * <b>Class.getDeclaredField(...)</b> returns matching field regardless of publicity of the exact class, excluding inheritance. <br/>
     * <b>Class.getField(...)</b> returns matching field that are public, including inheritance. <br/>
     * Neither of these can get a private or protected field from a superclass, or a public field from a protected or private class.
     *
     * @param clazz The class from which to search
     * @param name The name of the desired field;
     * @return A matching field the highest up the inheritance
     * @throws NoSuchFieldException if no such field is found within the inheritance.
     */
    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Field field;
        try {
            field = clazz.getDeclaredField(name);
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            if (clazz == Object.class) throw e;
            field = findField(clazz.getSuperclass(), name);
        }
        return field;
    }

    /**
     * <b>Class.getDeclaredMethod(...)</b> returns matching method regardless of publicity of the exact class, excluding inheritance. <br/>
     * <b>Class.getMethod(...)</b> returns matching method that are public, including inheritance. <br/>
     * Neither of these can get a private or protected method from a superclass, or a public method from a protected or private class.
     *
     * @param clazz The class from which to search
     * @param name The name of the desired method;
     * @param types The parameter classes for the desired method
     * @return A matching method the highest up the inheritance
     * @throws NoSuchMethodException if no such method is found within the inheritance.
     */
    private static Method findMethod(Class<?> clazz, String name, Class... types) throws NoSuchMethodException {
        Method method;
        try {
            method = clazz.getDeclaredMethod(name, types);
            method.setAccessible(true);
        } catch (NoSuchMethodException e) {
            if (clazz == Object.class) throw e;
            method = findMethod(clazz.getSuperclass(), name, types);
        }
        return method;
    }

    public static void sendActionBar(Player player, String message) {
        if (!player.isOnline()) {
            return; // Player may have logged out
        }
        if (!reflectionSetup) return; // Setting up reflection failed, can not continue.

        // Call the event, if cancelled don't send Action Bar
        ActionBarMessageEvent actionBarMessageEvent = new ActionBarMessageEvent(player, message);
        Bukkit.getPluginManager().callEvent(actionBarMessageEvent);
        if (actionBarMessageEvent.isCancelled())
            return;

        try {
            Object packet;
            if (useOldMethods) {
                Object cbc = iChatBaseComponentClass.cast(newChatBaseComponent.invoke(chatSerializerComponentClass, "{\"text\": \"" + message + "\"}"));
                packet = constPacketPlayOutChat.newInstance(cbc, (byte) 2);
            } else {
                if (useByteConstructor) {

                    Object chatCompontentText = constChatComponent.newInstance(message);
                    packet = constPacketPlayOutChat.newInstance(chatCompontentText, (byte) 2);
                }
                else {
                    Object chatCompontentText = constChatComponent.newInstance(message);
                    packet = constPacketPlayOutChat.newInstance(chatCompontentText, ChatMessageType_GAME_INFO);
                }
            }
            sendPlayerPacket(player, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Method getHandleMethod = null;
    private static Field playerConnectionField = null;
    private static Method sendPacketMethod = null;

    private static void sendPlayerPacket(Player pl, Object packet) {
        Object handle;

        Object craftPlayer = craftPlayerClass.cast(pl);

        try {
            if (getHandleMethod == null) getHandleMethod = findMethod(craftPlayerClass, "getHandle");
            handle = getHandleMethod.invoke(craftPlayer);

            if (playerConnectionField == null) playerConnectionField = findField(handle.getClass(), "playerConnection");
            Object connection = playerConnectionField.get(handle);

            if (sendPacketMethod == null) sendPacketMethod = findMethod(connection.getClass(), "sendPacket", packetClass);
            sendPacketMethod.invoke(connection, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendActionBar(final Player player, final String message, int duration) {
        sendActionBar(player, message);

        if (duration >= 0) {
            // Sends empty message at the end of the duration. Allows messages shorter than 3 seconds, ensures precision.
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendActionBar(player, "");
                }
            }.runTaskLater(plugin, duration + 1);
        }

        // Re-sends the messages every 3 seconds so it doesn't go away from the player's screen.
        while (duration > 40) {
            duration -= 40;
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendActionBar(player, message);
                }
            }.runTaskLater(plugin, (long) duration);
        }
    }

    public static void sendActionBarToAllPlayers(String message) {
        sendActionBarToAllPlayers(message, -1);
    }

    public static void sendActionBarToAllPlayers(String message, int duration) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendActionBar(p, message, duration);
        }
    }
}