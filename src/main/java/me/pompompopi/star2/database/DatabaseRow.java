package me.pompompopi.star2.database;

import java.sql.ResultSet;
import java.sql.SQLException;

public record DatabaseRow(long originalMessageId, long originalChannelId, long originalAuthorId,
                          long starboardMessageId, short stars) {
    public DatabaseRow(final ResultSet resultSet) throws SQLException {
        this(resultSet.getLong("original_message_id"), resultSet.getLong("original_channel_id"), resultSet.getLong("original_author_id"), resultSet.getLong("starboard_message_id"), resultSet.getShort("stars"));
    }
}
