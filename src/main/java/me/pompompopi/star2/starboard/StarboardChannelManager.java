package me.pompompopi.star2.starboard;

import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.database.DatabaseConnection;
import me.pompompopi.star2.database.DatabaseRow;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.RateLimitedException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class StarboardChannelManager {
    private static final int FUNCTIONAL_EMBED_LIMIT = 4095;
    private final DatabaseConnection databaseConnection;
    private final TextChannel starboardChannel;
    private final String starRaw;

    public StarboardChannelManager(final JDA jda, final Configuration configuration, final DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.starboardChannel = jda.getTextChannelById(configuration.getStarboardChannel());
        this.starRaw = configuration.getStarEmoji();
    }

    private void createEntry(final Message message, final short stars) throws RateLimitedException, SQLException {
        final Message starboardMessage = starboardChannel.sendMessageEmbeds(createEmbed(message, stars)).complete(true);
        databaseConnection.addBoardEntry(message.getIdLong(), message.getChannelIdLong(), starboardMessage.getIdLong(), stars);
    }

    private boolean updateEntry(final Message message, short stars, final DatabaseRow databaseRow) throws RateLimitedException, SQLException {
        final long originalMessageId = message.getIdLong();
        if (stars != -1) {
            if (stars != databaseRow.stars())
                databaseConnection.updateStars(originalMessageId, stars);
        } else {
            stars = databaseRow.stars();
        }
        starboardChannel.editMessageEmbedsById(databaseRow.starboardMessageId(), createEmbed(message, stars)).complete(true);
        return true;
    }

    public boolean updateOrCreateEntry(final Message message, final short stars) throws RateLimitedException, SQLException {
        return updateOrCreateEntry0(message, stars, true);
    }

    public boolean updateWithoutCreatingEntry(final Message message, final short stars) throws RateLimitedException, SQLException {
        return updateOrCreateEntry0(message, stars, false);
    }

    private boolean updateOrCreateEntry0(final Message message, final short stars, final boolean create) throws RateLimitedException, SQLException {
        final Optional<DatabaseRow> databaseRowOpt = databaseConnection.getBoardEntry(message.getIdLong());
        if (databaseRowOpt.isPresent())
            return updateEntry(message, stars, databaseRowOpt.get());
        if (!create)
            return false;

        createEntry(message, stars);
        return true;
    }

    public boolean removeEntry(final long originalMessageId) throws RateLimitedException, SQLException {
        final Optional<DatabaseRow> databaseRowOpt = databaseConnection.removeBoardEntry(originalMessageId);
        if (databaseRowOpt.isEmpty())
            return false;
        final DatabaseRow databaseRow = databaseRowOpt.get();
        starboardChannel.deleteMessageById(databaseRow.starboardMessageId()).complete(true);
        return true;
    }

    public MessageEmbed createEmbed(final Message message, final short stars) {
        final User author = message.getAuthor();
        final EmbedBuilder builder = new EmbedBuilder()
                .setColor(0xFDD835)
                .setTimestamp(message.getTimeEdited() == null ? message.getTimeCreated() : message.getTimeEdited())
                .setAuthor(author.getName())
                .setThumbnail(author.getEffectiveAvatarUrl())
                .setFooter(stars + " " + starRaw)
                .setTitle("Jump to Message")
                .setUrl(message.getJumpUrl());
        String messageContent = message.getContentRaw().trim();
        final List<Message.Attachment> attachments = message.getAttachments();
        final StringBuilder descriptionBuilder = new StringBuilder();
        if (!attachments.isEmpty()) {
            builder.setImage(attachments.getFirst().getUrl());
        } else {
            descriptionBuilder.append(messageContent);
        }

        builder.setDescription(descriptionBuilder);
        return builder.build();
    }
}