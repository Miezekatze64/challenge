package com.mieze.challenges;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.CommandAPIConfig;
import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.LiteralArgument;
import dev.jorel.commandapi.executors.PlayerCommandExecutor;

public class Main extends JavaPlugin implements Listener {
    private int amount = 0;
    private int current = 0;

    private Thread challengeThread = null;

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIConfig().silentLogs(true));
    }

    private List<Challenge> challenges = new ArrayList<>();

    private static boolean isValidItem(Material m) {
        return m.isItem() && !m.isAir();
    }

    private enum Challenge {
        FIND_ITEM("find_item", "Find an item of type %s", (vals, player) -> {
            final boolean contains[] = {false};
            player.getInventory().forEach(item -> {
                System.out.print(or(item, ItemStack::getType) + " | " + (Material)vals.get(0));
                if (item != null && item.getType() == (Material) vals.get(0)) {
                    Bukkit.broadcastMessage("found item!");
                    contains[0] = true;
                }
            });
            return contains[0];
        }, () -> {
            Material[] items = Material.values();
            Supplier<Material> f = () -> {
                return items[(int) (Math.random() * items.length)];
            };
            Material m = null;
            do {
                m = f.get();
            } while (isValidItem(m));
            return List.of(m);
        });

        private final String name;
        private final String description;

        private final BiFunction<List<?>, Player, Boolean> checkFunction;
        protected Supplier<List<?>> values;
        Challenge(String name, String description, BiFunction<List<?>, Player, Boolean> checkFunction, 
                Supplier<List<?>> initValue) {
            this.name = name;
            this.values = initValue;
            this.description = description;
            this.checkFunction = checkFunction;
        }

        public boolean check(List<?> vals, Player player) {
            return this.checkFunction.apply(vals, player);
        }

        public static Challenge getRandom() {
            return Challenge.values()[(int) (Math.random() * Challenge.values().length)];
        }
   }

    private Challenge nextChallenge() {
        if (challenges.size() >= Challenge.values().length) {
            challenges.clear();
            Bukkit.broadcastMessage("All challenges done! Resetting...");
        }

        Challenge newChallenge; 
        do {
            newChallenge = Challenge.getRandom();
        } while (challenges.contains(newChallenge));
        challenges.add(newChallenge);
        return newChallenge;
     }

    static private<T, U> U or(T a, Function<T, U> f) {
        return a == null ? null : f.apply(a);
    }

    static private<T> void orVoid(T a, Consumer<T> f) {
        if (a != null) f.accept(a);
    }

    private Runnable challengeRunnable(Challenge c, int seconds) {
        return () -> {
            var vals = c.values.get();
            var bar = Bukkit.createBossBar(c.description.formatted(vals.toArray()), BarColor.RED, BarStyle.SOLID);
            Bukkit.getOnlinePlayers().forEach(bar::addPlayer);
            var startTime = System.currentTimeMillis();
            try {
                while (true) {
//                    Bukkit.broadcastMessage("challenge: " + c.description.formatted("test"));
                    var winPlayers = Bukkit
                        .getOnlinePlayers()
                        .stream()
                        .map(x -> c.check(vals, x) ? x : null)
                        .filter(x -> x != null)
                        .toList();
                    if (winPlayers.size() > 0) {
                        Bukkit.broadcastMessage("Challange fullfilled! (Winners: %s)".formatted(winPlayers));
                        break;
                    }
//                    Bukkit.broadcastMessage("challange fullfilled?: " + check);

                    // update bar
                    var time = (System.currentTimeMillis() - startTime) / 1000.0;
                    if (time > seconds) {
                        Bukkit.broadcastMessage("Challange time ran out! (Not winners)");
                        break;
                    }
                    var progress = (float) time / (float) seconds;
                    bar.setProgress(progress);
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
            } finally {
                Bukkit.broadcastMessage("stopping running challenge...");
                bar.removeAll();
            }
        };
    }

    private PlayerCommandExecutor handleCommand(String command) {
        return switch (command) {
            case "start" -> (a, b) -> {
                Bukkit.broadcastMessage("starting challenges [%d]...".formatted(b[0]));
                this.amount = (int) b[0];
                var challenge = nextChallenge();
                orVoid(this.challengeThread, Thread::interrupt);
                this.challengeThread = new Thread(challengeRunnable(challenge, 10));
                this.challengeThread.start();
            };
            case "stop" -> (a, b) -> {
                 orVoid(this.challengeThread, Thread::interrupt);
                 this.challengeThread = null;
            };
            default -> (_x, _y) -> Bukkit.broadcastMessage("invalid command `/challenge " + command + "'!");
        };
    }

    private void addCommandVariant(String s, Argument<?>... args) {
        Stream<Argument<?>> a = Stream
            .of(Stream.of(new LiteralArgument(s)), Stream.of(args))
            .flatMap(x -> x);

        new CommandAPICommand("challenge")
            .withArguments(a.toList())
            .executesPlayer(handleCommand(s))
            .register();
    }

    private void registerCommand() {
        addCommandVariant("start", new IntegerArgument("amount"));
        addCommandVariant("stop");
    }
    
    @Override
    public void onEnable() {
        CommandAPI.onEnable(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getLogger().info("Hello, World!");
        
        registerCommand();
    }
}
