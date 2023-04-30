package com.mieze.extended_taming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public final class Util {
    public static UUID bytesToUUID(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }
    public static byte[] UUIDtoBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
    public static byte[] intToArray(int i) {
        return ByteBuffer.allocate(4).putInt(i).array();
    }
    public static int arrayToInt(byte[] arr) {
        return ByteBuffer.wrap(arr).asIntBuffer().get();
    }

    public static void writeInt(BufferedOutputStream stream, int v) throws IOException {
        stream.write(intToArray(v));
    }
    public static void writeBool(BufferedOutputStream stream, boolean v) throws IOException {
        stream.write(new byte[]{v ? (byte)1 : (byte)0});
    }
    public static void writeUUID(BufferedOutputStream stream, UUID v) throws IOException {
        stream.write(UUIDtoBytes(v));
    }
    public static int readInt(BufferedInputStream stream) throws IOException {
        return Util.arrayToInt(stream.readNBytes(4));
    }
    public static UUID readUUID(BufferedInputStream stream) throws IOException {
        return Util.bytesToUUID(stream.readNBytes(16));
    }
    public static boolean readBool(BufferedInputStream stream) throws IOException {
        return stream.read() != 0;
    }
    public static String getName(Entity ent) {
        return (ent.getCustomName() != null ?
                ent.getCustomName() :
                ent.getName());
    }

    public static void actionBar(Player player, String msg) {
        player
            .spigot()
            .sendMessage(ChatMessageType.ACTION_BAR,
                         new TextComponent(msg));
    }
}
