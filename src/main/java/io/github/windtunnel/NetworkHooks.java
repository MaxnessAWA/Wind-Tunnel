package io.github.windtunnel;

import io.github.windtunnel.network.SyncWindTunnelControllerPayload;
import io.github.windtunnel.network.SyncGogglesForceVectorsPayload;
import io.github.windtunnel.network.SyncHologramProjectorPayload;
import io.github.windtunnel.network.SyncAirflowInjectorDiagramPayload;
import io.github.windtunnel.network.SyncWindTunnelMountDiagramPayload;
import io.github.windtunnel.network.UpdateAirflowInjectorPayload;
import io.github.windtunnel.network.RequestAirflowInjectorDiagramPayload;
import io.github.windtunnel.network.RequestWindTunnelMountDiagramPayload;
import io.github.windtunnel.network.UpdateWindTunnelControllerPayload;
import io.github.windtunnel.network.UpdateWindTunnelMountPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.Objects;

/**
 * Centralized network payload registration.
 * <p>
 * All custom packets are registered on the Neoforge payload registrar with protocol version "1".
 * The payloads are split into two categories:
 * <ul>
 * <li><b>Play-to-server</b> — Client-initiated edits: controller settings, mount pose, injector
 *     configuration, and diagram requests.</li>
 * <li><b>Play-to-client</b> — Server-authoritative syncs: controller state echo, mount diagram
 *     snapshots, and injector diagram data.</li>
 * </ul>
 */
public final class NetworkHooks {
    private NetworkHooks() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // This protocol version is only for this mod's custom payloads.
        var registrar = event.registrar("1");

        // ---- Client-to-server payloads (edits initiated by GUI screens) ----
        registrar.playToServer(Objects.requireNonNull(UpdateWindTunnelControllerPayload.TYPE),
                Objects.requireNonNull(UpdateWindTunnelControllerPayload.STREAM_CODEC),
                UpdateWindTunnelControllerPayload::handle);
        registrar.playToServer(Objects.requireNonNull(UpdateWindTunnelMountPayload.TYPE),
                Objects.requireNonNull(UpdateWindTunnelMountPayload.STREAM_CODEC),
                UpdateWindTunnelMountPayload::handle);
        registrar.playToServer(Objects.requireNonNull(UpdateAirflowInjectorPayload.TYPE),
                Objects.requireNonNull(UpdateAirflowInjectorPayload.STREAM_CODEC),
                UpdateAirflowInjectorPayload::handle);
        registrar.playToServer(Objects.requireNonNull(RequestWindTunnelMountDiagramPayload.TYPE),
                Objects.requireNonNull(RequestWindTunnelMountDiagramPayload.STREAM_CODEC),
                RequestWindTunnelMountDiagramPayload::handle);
        registrar.playToServer(Objects.requireNonNull(RequestAirflowInjectorDiagramPayload.TYPE),
                Objects.requireNonNull(RequestAirflowInjectorDiagramPayload.STREAM_CODEC),
                RequestAirflowInjectorDiagramPayload::handle);

        // ---- Server-to-client payloads (server-authoritative state sync) ----
        registrar.playToClient(Objects.requireNonNull(SyncWindTunnelControllerPayload.TYPE),
                Objects.requireNonNull(SyncWindTunnelControllerPayload.STREAM_CODEC),
                SyncWindTunnelControllerPayload::handle);
        registrar.playToClient(Objects.requireNonNull(SyncWindTunnelMountDiagramPayload.TYPE),
                Objects.requireNonNull(SyncWindTunnelMountDiagramPayload.STREAM_CODEC),
                SyncWindTunnelMountDiagramPayload::handle);
        registrar.playToClient(Objects.requireNonNull(SyncAirflowInjectorDiagramPayload.TYPE),
                Objects.requireNonNull(SyncAirflowInjectorDiagramPayload.STREAM_CODEC),
                SyncAirflowInjectorDiagramPayload::handle);
        registrar.playToClient(Objects.requireNonNull(SyncHologramProjectorPayload.TYPE),
                Objects.requireNonNull(SyncHologramProjectorPayload.STREAM_CODEC),
                SyncHologramProjectorPayload::handle);
        registrar.playToClient(Objects.requireNonNull(SyncGogglesForceVectorsPayload.TYPE),
                Objects.requireNonNull(SyncGogglesForceVectorsPayload.STREAM_CODEC),
                SyncGogglesForceVectorsPayload::handle);
    }
}
