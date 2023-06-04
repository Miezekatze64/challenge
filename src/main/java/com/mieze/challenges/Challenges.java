package com.mieze.challenges;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.CommandAPIConfig;
import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.LiteralArgument;
import dev.jorel.commandapi.executors.PlayerCommandExecutor;

public class Challenges extends JavaPlugin implements Listener {
    private static Challenges instance = null;

    private int amount = 0;
    private int current = 0;
    private Thread challengeThread = null;
    private HashMap<String, Integer> scores = new HashMap<>();

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIConfig().silentLogs(true));
    }

    private List<Class<? extends Challenge>> challengeClasses = new ArrayList<>();
    private List<Class<? extends Challenge>> usedChallenges = new ArrayList<>();

    public interface Challenge {
        public String getDescription();
        public int getMaxTime();
        public boolean isFullfilledBy(Player player);
    }

    private Class<? extends Challenge> getRandomChallenge() {
        return this.challengeClasses.get((int) (Math.random() * this.challengeClasses.size()));
    }

    private Class<? extends Challenge> nextChallenge() {
        if (usedChallenges.size() >= challengeClasses.size()) {
            usedChallenges.clear();
            sendMessage("All challenges done! Reshuffling...", 1);
        }

        Class<? extends Challenge> newChallenge; 
        do {
            newChallenge = getRandomChallenge();
        } while (usedChallenges.contains(newChallenge));
        usedChallenges.add(newChallenge);
        return newChallenge;
     }

    static private<T, U> U or(T a, Function<T, U> f) {
        return a == null ? null : f.apply(a);
    }

    static private<T> void orVoid(T a, Consumer<T> f) {
        if (a != null) f.accept(a);
    }

    private static String timeString(int secs) {
        return "%d:%02d".formatted(secs / 60, secs % 60);
    }

    private static void sendMessage(String msg, int lvl) {
        Bukkit.broadcastMessage(ChatColor.BOLD + "" + (lvl == 0 ? ChatColor.GREEN : lvl == 1 ? ChatColor.YELLOW : ChatColor.RED) + 
                "[challenges] " + ChatColor.WHITE + msg);
    }

    private Runnable challengeRunnable(Class<? extends Challenge> cc) {
        return () -> {
            final Challenge c;
            try {
                System.out.println(Arrays.toString(cc.getDeclaredConstructors()));
                var constr = cc.getDeclaredConstructor();
                c = constr.newInstance();
            } catch (InvocationTargetException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
                sendMessage("contructor error: Error: " + e.getCause().getMessage(), 2);
                return;
            }
            var secs = c.getMaxTime();
            var barText = c.getDescription();
            var bar = Bukkit.createBossBar("loading...", BarColor.RED, BarStyle.SOLID);
            sendMessage("New challenge: " + barText, 1);
            Bukkit.getOnlinePlayers().forEach(bar::addPlayer);
            var startTime = System.currentTimeMillis();
            try {
                while (true) {
                    var winPlayers = Bukkit
                        .getOnlinePlayers()
                        .stream()
                        .map(x -> c.isFullfilledBy(x) ? x : null)
                        .filter(x -> x != null)
                        .toList();
                    if (winPlayers.size() > 0) {
                        winPlayers.forEach(x -> {
                            sendMessage(x.getName() + " +1!", 0);
                            scores.put(x.getName(), scores.getOrDefault(x.getName(), 0) + 1);
                        });
                        break;
                    }

                    // update bar
                    var time = (System.currentTimeMillis() - startTime) / 1000.0;
                    if (time > secs) {
                        sendMessage("Challange time ran out! (No winners)", 2);
                        break;
                    }
                    var progress = (float) time / (float) secs;
                    bar.setProgress(progress);

                    var left = secs - (int) time;
                    bar.setTitle(ChatColor.RED + "[%d/%d] ".formatted(this.current+1, this.amount) + 
                            ChatColor.WHITE + barText + " [%s left]".formatted(timeString(left)));

                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
            } finally {
                bar.removeAll();
                this.current++;
                if (this.current < this.amount) {
                    startNextChallenge();
                } else {
                    var winners = scores
                        .entrySet()
                        .stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .collect(Collectors.toList());
                    if (winners.size() > 0) {
                        var winPoints = winners.get(0).getValue();
                        winners
                            .stream()
                            .takeWhile(x -> x.getValue() == winPoints)
                            .forEach(x -> {
                                sendMessage(x.getKey() + " won the game with " + x.getValue() + " points!", 0);
                                var player = Bukkit.getPlayer(x.getKey());
                                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 100, 1);
                            });
                    }
                }
            }
        };
    }

    private void startNextChallenge() {
        sendMessage("starting challenge [%d/%d]...".formatted(this.current+1, this.amount), 1);
        var challenge = nextChallenge();
        orVoid(this.challengeThread, Thread::interrupt);
        this.challengeThread = new Thread(challengeRunnable(challenge));
        this.challengeThread.start();
    }

    private PlayerCommandExecutor handleCommand(String command) {
        return switch (command) {
            case "start" -> (a, b) -> {
                this.current = 0;
                this.amount = (int) b[0];
                startNextChallenge();
            };
            case "stop" -> (a, b) -> {
                 orVoid(this.challengeThread, Thread::interrupt);
                 this.challengeThread = null;
            };
            default -> (_x, _y) -> Bukkit.broadcastMessage("invalid command `/challenge " + command + "'! (THIS IS A PLUGIN BUG)");
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

    private void registerChallenge(Class<? extends Challenge> c) {
        challengeClasses.add(c);
    }

    public Challenges getInstance() {
        return Challenges.instance;
    }
    
    @Override
    public void onEnable() {
        Challenges.instance = this;
        CommandAPI.onEnable(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getLogger().info("Hello, World!");

        registerCommand();
        registerChallenge(FindItem.class);
    }

    @Override
    public void onDisable() {
        CommandAPI.onDisable();
        // stop all future challenges
        this.current = this.amount;
        orVoid(this.challengeThread, Thread::interrupt);
        Bukkit.getLogger().info("Goodbye, World!");
    }
}
