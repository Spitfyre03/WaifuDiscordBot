package com.github.waifu.interactions;

import com.github.waifu.interactions.buttons.IButtonInteraction;
import com.github.waifu.interactions.slash.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.internal.utils.Checks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class InteractionCenter extends ListenerAdapter {

    public static final Logger LOGGER = LoggerFactory.getLogger(InteractionCenter.class);
    private static final InteractionCenter singleton = new InteractionCenter();

    private final Map<String, ISlashInteraction> slashHandlers = new HashMap<>();
    private final Map<String, IButtonInteraction> buttonHandlers = new HashMap<>();

    private InteractionCenter() {}

    public static InteractionCenter getSingleton() { return singleton; }

    // should be called after bot starts up
    public void registerCommands(@Nonnull JDA bot) {
        LOGGER.info("Registering slash commands for Waifu");
        // Add a new instance of your command handler here to register it
        List<ISlashInteraction> commandsToRegister = List.of(
            new SlashNh(),
            new SlashBasicResponse("ping", "Pong!", "Ping test"),
            new SlashBasicResponse("bing", "Bong!", "Bing bong!"),
            new SlashBaseTenorSearch("smashing", "Smashing!", "nigel thornberry smashing"),
            new SlashBaseTenorSearch("cagemebro", "I'm going to steal the Declaration of Independence", "nick cage"),
            new SlashBaseTenorSearch("deuces", "Peace bitches", "deuces"),
            new SlashPokemon(),
            new SlashMagic8(),
            new SlashEmote(),
            new SlashBasicResponse("oauth2",
                "https://discord.com/api/oauth2/authorize?client_id=933960413534617611&permissions=1574075624529&scope=bot%20applications.commands",
                "Get the invite link for this bot")
        );

        CompletableFuture<List<Command>> globalCommandsFuture = bot.retrieveCommands().submit();
        Map<Long, CompletableFuture<List<Command>>> guildCommandsFuture = new HashMap<>();
        bot.getGuilds().forEach(g -> {
            guildCommandsFuture.put(g.getIdLong(), g.retrieveCommands().submit());
        });
        CompletableFuture.allOf(guildCommandsFuture.values().toArray(new CompletableFuture[0])).thenAcceptBothAsync(
            globalCommandsFuture, (_void, globalCommands) -> {
                Map<Long, List<Command>> guildCommands = guildCommandsFuture
                    .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().join()));
                List<CompletableFuture<Command>> globalQueued = new ArrayList<>();
                List<CompletableFuture<Command>> guildQueued = new ArrayList<>();
                commandsToRegister.forEach(t -> {
                    // TODO Discord uses the crazy regex as the naming rule for commands
                    // ^[-_\p{L}\p{N}\p{sc=Deva}\p{sc=Thai}]{1,32}$
                    try {
                        Checks.notBlank(t.getName(), "Command name");
                    }
                    catch (IllegalStateException e) {
                        LOGGER.error(
                            String.format(
                                "A command of Type %s with no name was provided. It will not be registered.",
                                t.getClass().getSimpleName())
                            , e
                        );
                        return;
                    }

                    List<Long> guildsToRegister = t.getGuilds();
                    if (Optional.ofNullable(guildsToRegister).orElse(List.of()).isEmpty()) {
                        globalQueued.add(bot.upsertCommand(t.getCommand()).submit());
                        LOGGER.debug(String.format("Global command %s queued to register", t.getName()));
                        globalCommands.removeIf(gCmd -> gCmd.getName().equals(t.getName()));
                    }
                    else {
                        guildsToRegister.forEach(g -> {
                            Guild guildToRegister = bot.getGuildById(g);
                            if (guildToRegister != null) {
                                guildQueued.add(guildToRegister.upsertCommand(t.getCommand()).submit());
                                LOGGER.debug(String.format("Command %s queued to register for guild %d", t.getName(), g));
                                guildCommands.get(g).removeIf(gCmd -> gCmd.getName().equals(t.getName()));
                            }
                        });
                    }
                    slashHandlers.put(t.getName(), t);

                    if (t instanceof IButtonInteraction handler) {
                        handler.getButtons().forEach(b -> {
                            LOGGER.debug(String.format("Registering button handler %s for command %s",
                                b.getId(),
                                t.getName()));
                            buttonHandlers.put(b.getId(), handler);
                        });
                    }
                });
                CompletableFuture.allOf(globalQueued.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
                    globalCommands.forEach(cmd -> {
                        if (cmd != null) {
                            cmd.delete().queue();
                            LOGGER.info(String.format("Queued deletion of global command %s", cmd.getName()));
                        }
                    });
                });
                CompletableFuture.allOf(guildQueued.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
                    guildCommands.forEach(
                        (key, val) -> val.forEach(cmd -> {
                            if (cmd != null) {
                                cmd.delete().queue();
                                LOGGER.info(String.format("Queued deletion of command %s for guild %d", cmd.getName(), key));
                            }
                        })
                    );
                });
            }
        );
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        LOGGER.debug("Capturing slash event for command " + event.getName());
        ISlashInteraction command = slashHandlers.get(event.getName());
        try {
            if (command != null) command.onCommand(event);
        }
        catch (Exception e) {
            LOGGER.error("An uncaught exception was thrown while processing a slash command.", e);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        LOGGER.debug("Capturing button interaction event for button " + event.getComponentId());
        IButtonInteraction handler = buttonHandlers.get(event.getComponentId());
        try {
            if (handler != null) handler.onInteract(event);
        }
        catch (Exception e) {
            LOGGER.error("An uncaught exception was thrown while processing a button interaction.", e);
        }
    }
}
