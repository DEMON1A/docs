package com.sismics.docs.core.service;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.dao.jpa.TagDao;
import com.sismics.docs.core.event.DocumentCreatedAsyncEvent;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.Tag;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.DocumentUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.EmailUtil;
import com.sismics.util.context.ThreadLocalContext;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Inbox scanning service.
 *
 * @author bgamard
 */
public class InboxService extends AbstractScheduledService {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /**
     * Last synchronization data.
     */
    private Date lastSyncDate;
    private int lastSyncMessageCount = 0;
    private String lastSyncError;

    public InboxService() {
    }

    @Override
    protected void startUp() {
    }

    @Override
    protected void shutDown() {
    }
    
    @Override
    protected void runOneIteration() {
        syncInbox();
    }

    /**
     * Synchronize the inbox.
     */
    public void syncInbox() {
        TransactionUtil.handle(new Runnable() {
            @Override
            public void run() {
                Boolean enabled = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_ENABLED);
                if (!enabled) {
                    return;
                }

                Folder inbox = null;
                lastSyncError = null;
                lastSyncDate = new Date();
                lastSyncMessageCount = 0;
                try {
                    inbox = openInbox();
                    int count = inbox.getMessageCount();
                    Message[] messages = inbox.getMessages(1, count);

                    for (Message message : messages) {
                        if (!message.getFlags().contains(Flags.Flag.SEEN)) {
                            importMessage(message);
                            lastSyncMessageCount++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Error synching the inbox", e);
                    lastSyncError = e.getMessage();
                } finally {
                    try {
                        if (inbox != null) {
                            inbox.close(false);
                            inbox.getStore().close();
                        }
                    } catch (Exception e) {
                        // NOP
                    }
                }
            }
        });
    }

    /**
     * Test the inbox configuration.
     *
     * @return Number of messages currently in the remote inbox
     */
    public int testInbox() {
        Boolean enabled = ConfigUtil.getConfigBooleanValue(ConfigType.INBOX_ENABLED);
        if (!enabled) {
            return -1;
        }

        Folder inbox = null;
        try {
            inbox = openInbox();
            return inbox.getMessageCount();
        } catch (Exception e) {
            log.error("Error testing inbox", e);
            return -1;
        } finally {
            try {
                if (inbox != null) {
                    inbox.close(false);
                    inbox.getStore().close();
                }
            } catch (Exception e) {
                // NOP
            }
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 15, TimeUnit.MINUTES);
    }

    /**
     * Open the remote inbox.
     *
     * @return Opened inbox folder
     * @throws Exception e
     */
    private Folder openInbox() throws Exception {
        Properties properties = new Properties();
        String port = ConfigUtil.getConfigStringValue(ConfigType.INBOX_PORT);
        properties.put("mail.imap.host", ConfigUtil.getConfigStringValue(ConfigType.INBOX_HOSTNAME));
        properties.put("mail.imap.port", port);
        boolean isSsl = "993".equals(port);
        properties.put("mail.imap.ssl.enable", String.valueOf(isSsl));
        properties.setProperty("mail.imap.socketFactory.class",
                isSsl ? "javax.net.ssl.SSLSocketFactory" : "javax.net.DefaultSocketFactory");
        properties.setProperty("mail.imap.socketFactory.fallback", "true");
        properties.setProperty("mail.imap.socketFactory.port", port);

        Session session = Session.getDefaultInstance(properties);

        Store store = session.getStore("imap");
        store.connect(ConfigUtil.getConfigStringValue(ConfigType.INBOX_USERNAME),
                ConfigUtil.getConfigStringValue(ConfigType.INBOX_PASSWORD));

        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        return inbox;
    }

    /**
     * Import an email.
     *
     * @param message Message
     * @throws Exception e
     */
    private void importMessage(Message message) throws Exception {
        // Parse the mail
        EmailUtil.MailContent mailContent = new EmailUtil.MailContent();
        mailContent.setSubject(message.getSubject());
        mailContent.setDate(message.getSentDate());
        EmailUtil.parseMailContent(message, mailContent);

        // Create the document
        Document document = new Document();
        document.setUserId("admin");
        if (mailContent.getSubject() == null) {
            document.setTitle("Imported email from EML file");
        } else {
            document.setTitle(StringUtils.abbreviate(mailContent.getSubject(), 100));
        }
        document.setDescription(StringUtils.abbreviate(mailContent.getMessage(), 4000));
        document.setSubject(StringUtils.abbreviate(mailContent.getSubject(), 500));
        document.setFormat("EML");
        document.setSource("Inbox");
        document.setLanguage(ConfigUtil.getConfigStringValue(ConfigType.DEFAULT_LANGUAGE));
        if (mailContent.getDate() == null) {
            document.setCreateDate(new Date());
        } else {
            document.setCreateDate(mailContent.getDate());
        }

        // Save the document, create the base ACLs
        document = DocumentUtil.createDocument(document, "admin");

        // Add the tag
        String tagId = ConfigUtil.getConfigStringValue(ConfigType.INBOX_TAG);
        if (tagId != null) {
            TagDao tagDao = new TagDao();
            Tag tag = tagDao.getById(tagId);
            if (tag != null) {
                tagDao.updateTagList(document.getId(), Sets.newHashSet(tagId));
            }
        }

        // Raise a document created event
        DocumentCreatedAsyncEvent documentCreatedAsyncEvent = new DocumentCreatedAsyncEvent();
        documentCreatedAsyncEvent.setUserId("admin");
        documentCreatedAsyncEvent.setDocument(document);
        ThreadLocalContext.get().addAsyncEvent(documentCreatedAsyncEvent);

        // Add files to the document
        for (EmailUtil.FileContent fileContent : mailContent.getFileContentList()) {
            FileUtil.createFile(fileContent.getName(), fileContent.getFile(), fileContent.getSize(), "eng", "admin", document.getId());
        }
    }

    public Date getLastSyncDate() {
        return lastSyncDate;
    }

    public int getLastSyncMessageCount() {
        return lastSyncMessageCount;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }
}
