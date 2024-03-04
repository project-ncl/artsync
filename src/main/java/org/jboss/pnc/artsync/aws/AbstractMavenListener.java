package org.jboss.pnc.artsync.aws;

import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferListener;

public class AbstractMavenListener implements RepositoryListener, TransferListener {
    @Override
    public void artifactDescriptorInvalid(RepositoryEvent event) {}

    @Override
    public void artifactDescriptorMissing(RepositoryEvent event) {}

    @Override
    public void metadataInvalid(RepositoryEvent event) {}

    @Override
    public void artifactResolving(RepositoryEvent event) {}

    @Override
    public void artifactResolved(RepositoryEvent event) {}

    @Override
    public void metadataResolving(RepositoryEvent event) {}

    @Override
    public void metadataResolved(RepositoryEvent event) {}

    @Override
    public void artifactDownloading(RepositoryEvent event) {}

    @Override
    public void artifactDownloaded(RepositoryEvent event) {}

    @Override
    public void metadataDownloading(RepositoryEvent event) {}

    @Override
    public void metadataDownloaded(RepositoryEvent event) {}

    @Override
    public void artifactInstalling(RepositoryEvent event) {}

    @Override
    public void artifactInstalled(RepositoryEvent event) {}

    @Override
    public void metadataInstalling(RepositoryEvent event) {}

    @Override
    public void metadataInstalled(RepositoryEvent event) {}

    @Override
    public void artifactDeploying(RepositoryEvent event) {}

    @Override
    public void artifactDeployed(RepositoryEvent event) {}

    @Override
    public void metadataDeploying(RepositoryEvent event) {}

    @Override
    public void metadataDeployed(RepositoryEvent event) {}

    @Override
    public void transferInitiated(TransferEvent event) {}

    @Override
    public void transferStarted(TransferEvent event) {}

    @Override
    public void transferProgressed(TransferEvent event) {}

    @Override
    public void transferCorrupted(TransferEvent event) {}

    @Override
    public void transferSucceeded(TransferEvent event) {}

    @Override
    public void transferFailed(TransferEvent event) {}
}
