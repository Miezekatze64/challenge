package com.mieze.extended_taming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class Main extends JavaPlugin implements Listener {
    private record TamingInfo(Material item, double probability, int types) {};
    private final class TameType {
        public static final int FOLLOW = 0b1;
        public static final int FIGHT  = 0b10;
    }

    private static class TamedInfo {
        private final int types;
        private final UUID player;
        private boolean sitting = false;

        public TamedInfo(Entity entity, int types, UUID player,
                         boolean sitting) {
            this.types = types;
            this.player = player;
            this.sitting = sitting;
            ((Mob)entity).setAware(! this.sitting);
        }

        public void setSitting(boolean b) {
            this.sitting = b;
        }

        public boolean isSitting() {
            return this.sitting;
        }

        public void writeToStream(BufferedOutputStream stream)
            throws IOException {
            Util.writeInt(stream, types);
            Util.writeUUID(stream, player);
            Util.writeBool(stream, sitting);
        }

        public static TamedInfo readFromStream(Entity entity,
                                               BufferedInputStream stream)
                                               throws IOException {
            int types = Util.readInt(stream);
            UUID player = Util.readUUID(stream);
            boolean sitting = Util.readBool(stream);
            return new TamedInfo(entity, types, player, sitting);
        }

        @Override
        public String toString() {
            return "TamedInfo { types = " + this.types +
                ", player = " + this.player + " }";
        }
    }

    private HashMap<EntityType, TamingInfo> tames = new HashMap<>();
    private HashMap<UUID, TamedInfo> tamed = new HashMap<>();
    private HashMap<UUID, Long> cooldowns = new HashMap<>();

    private void saveState() {
        try {
            File dir = new File("./plugins/extended_taming/");
            if (!dir.exists())
                dir.mkdirs();

            File file = new File(dir, "state.dat");
            if (!file.exists())
                file.createNewFile();
            var stream = new BufferedOutputStream(new FileOutputStream(file));
            // magic number
            stream.write(new byte[] { 'D', 'A', 'T', 'A' });
            // number of entries
            Util.writeInt(stream, tamed.size());
            for (var a : tamed.keySet()) {
                Util.writeUUID(stream, a);
                tamed.get(a)
                    .writeToStream(stream);
            }
            stream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void restoreState() {
        try {
            File file = new File("./plugins/extended_taming/state.dat");
            if (!file.exists())
                return;

            var stream = new BufferedInputStream(new FileInputStream(file));
            // magic number
            var magic = stream.readNBytes(4);
            if (!Arrays.equals(magic, new byte[] { 'D', 'A', 'T', 'A' })) {
                Bukkit.getLogger().log(Level.SEVERE,
                                       "Invalid data file! cannot parse: " +
                                       Arrays.toString(magic));
                return;
            }
            // number of entries
            int entries = Util.readInt(stream);
            var map = new HashMap<UUID, TamedInfo>();
            for (int i = 0; i < entries; i++) {
                UUID key = Util.readUUID(stream);
                if (Bukkit.getEntity(key) == null)
                    break;
                map.put(key, TamedInfo.readFromStream(Bukkit.getEntity(key),
                                                      stream));
            }

            Bukkit.getLogger().info(map.toString());

            this.tamed = map;
            stream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T or(T a, T b) {
        return (a == null) ? b : a;
    }

    private long cooldown = 200l;
    private int tp_chunks = 1;

    private void loadConfig() {
        File file = new File("./plugins/extended_taming/config.yml");
        var config = YamlConfiguration.loadConfiguration(file);
        tp_chunks = config.getInt("tp_chunks", 1);
        cooldown = config.getLong("cooldown", 200l);

        var mobs = config.getMapList("mobs");
        mobs.forEach(mob -> {
            String name = (String)mob.get("name");
            if (name == null)
                return;

            String food = or((String)mob.get("food"), "air");
            boolean follow = or((Boolean)mob.get("follow"), false);
            boolean fight = or((Boolean)mob.get("fight"), false);
            double prob = or((Double)mob.get("probability"), 0.1);

            tames.put(EntityType.fromName(name),
                  new TamingInfo(Material.matchMaterial(food), prob,
                                 (follow ? TameType.FOLLOW : 0)
                                 | (fight ? TameType.FIGHT : 0)));
            Bukkit.getLogger().info(("loaded {name=\"%s\", "+
                                    "food=\"%s\", follow=%b, "+
                                    "fight=%b,prob=%f}"
                                    ).formatted(name, food, follow, fight,
                                               prob));
        });
    }

    @Override
    public void onLoad() {
        this.restoreState();
    }

    @Override
    public void onEnable() {
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void playerInteract(PlayerInteractEntityEvent event) {
        var entity = event.getRightClicked();
        var player = event.getPlayer();

        // reset cooldown
        var player_cooldown = this.cooldowns
            .get(event.getPlayer().getUniqueId());
        if (player_cooldown != null &&
            System.currentTimeMillis() - player_cooldown < this.cooldown)
            return;

        this.cooldowns.put(player.getUniqueId(),
                           System.currentTimeMillis());


        if (!tames.containsKey(entity.getType()))
            return;
        var tamingInfo = tames.get(entity.getType());

        if (!player.isSneaking()) {
            // check if already tamed
            if (tamed.containsKey(entity.getUniqueId()))
                return;

            // get item
            var item = event
                    .getPlayer()
                    .getInventory()
                    .getItemInMainHand();
            if (tamingInfo.item() != item.getType())
                return;

            // remove item
            item.setAmount(item.getAmount() - 1);
            // check if tamed (check prob.)
            if (Math.random() >= tamingInfo.probability())
                return;

            // show message if tamed
            Util.actionBar(player, "Tamed: " + Util.getName(entity));
            // set tamed
            tamed.put(entity.getUniqueId(),
                      new TamedInfo(entity, tamingInfo.types(), player
                                    .getUniqueId(), false));
            // spawn hearts
            player.spawnParticle(Particle.HEART, entity
                                            .getLocation().add(0, 1, 0), 100);
        } else {
            // check if tamed
            if (! tamed.containsKey(entity.getUniqueId()))
                return;

            var info = tamed.get(entity.getUniqueId());
            if (info.player != event.getPlayer().getUniqueId())
                return;
            if (info.isSitting()) {
                // set to following (is aware of surroundings)
                ((Mob)entity).setAware(true);
                info.setSitting(false);
                Util.actionBar(player, Util.getName(entity) +
                               " is following you");
            } else {
                // set to sitting
                ((Mob)entity).setAware(false);
                entity.setVelocity(new Vector(0, 0, 0));
                info.setSitting(true);
                Util.actionBar(player, Util.getName(entity) +
                               " is sitting");
            }
        }
        saveState();
        event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() == null)
            return;
        if (!tamed.containsKey(event.getEntity().getUniqueId()))
            return;

        if (event.getTarget().getUniqueId() ==
            tamed.get(event.getEntity().getUniqueId()).player) {
              event.setTarget(null);
              event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        var c1 = event.getFrom().getChunk();
        var c2 = event.getTo().getChunk();
        if (c1.equals(c2)) return;

        tamed.forEach((uuid, b) -> {
            if ((b.types & TameType.FOLLOW) == 0) return;
            if (! b.player.equals(event.getPlayer().getUniqueId())) return;
            var entity = Bukkit.getEntity(uuid);

            if (entity.getLocation().distance(event.getTo())
                < this.tp_chunks * 16)
                return;

            entity.teleport(event.getPlayer());
            System.out.println("Teleported: " + Bukkit.getEntity(uuid));
        });
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        var entity = event.getEntity();
        if (! tamed.containsKey(entity.getUniqueId())) return;
        var info = tamed.get(entity.getUniqueId());

        Util.actionBar(Bukkit.getPlayer(info.player),
                       Util.getName(entity) + " died!");

        tamed.remove(entity.getUniqueId());
    }

    @EventHandler
    public void onPlayerAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        tamed.forEach((uuid, info) -> {
            if ((info.types & TameType.FIGHT) == 0)
                return;
            if (info.player != event.getEntity().getUniqueId()) return;
            ((Mob)Bukkit
                .getEntity(uuid))
                .setTarget((LivingEntity)event.getDamager());
        });
    }
}
