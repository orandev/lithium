//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk.assets;

import com.waz.model.Messages;

import java.util.UUID;

public class MessageText implements IGeneric {
    private final String text;
    private final UUID mentionUser;
    private final int offset;
    private final int len;
    private final long expires;
    private UUID messageId = UUID.randomUUID();

    public MessageText(String text) {
        this(text, 0, null, 0, 0);
    }

    public MessageText(String text, long expires) {
        this(text, expires, null, 0, 0);
    }

    public MessageText(String text, long expires, UUID mentionUser, int offset, int len) {
        this.text = text;
        this.mentionUser = mentionUser;
        this.offset = offset;
        this.len = len;
        this.expires = expires;
    }

    @Override
    public Messages.GenericMessage createGenericMsg() {
        Messages.GenericMessage.Builder ret = Messages.GenericMessage.newBuilder()
                .setMessageId(getMessageId().toString());

        Messages.Text.Builder text = Messages.Text.newBuilder()
                .setContent(this.text)
                .setExpectsReadConfirmation(true);

        if (mentionUser != null) {
            Messages.Mention.Builder mention = Messages.Mention.newBuilder()
                    .setUserId(mentionUser.toString())
                    .setLength(len)
                    .setStart(offset);

            text.addMentions(mention);
        }

        if (expires > 0) {
            Messages.Ephemeral.Builder ephemeral = Messages.Ephemeral.newBuilder()
                    .setText(text)
                    .setExpireAfterMillis(expires);

            return ret
                    .setEphemeral(ephemeral)
                    .build();
        }

        return ret
                .setText(text)
                .build();
    }

    @Override
    public UUID getMessageId() {
        return messageId;
    }
}
