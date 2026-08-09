package name.caiyao.fakegps.ui.screen.verify

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.TransportSchemaContract
import name.caiyao.fakegps.verify.DeviceObserver
import name.caiyao.fakegps.verify.HookApplicability
import name.caiyao.fakegps.verify.HookVerificationClient
import name.caiyao.fakegps.verify.ObservationScope
import name.caiyao.fakegps.verify.ProbeClientResult
import name.caiyao.fakegps.verify.ProbeFailure
import name.caiyao.fakegps.verify.ProbeRequest
import name.caiyao.fakegps.verify.ProbeUiStatus
import name.caiyao.fakegps.verify.ProbeVerificationDecision
import name.caiyao.fakegps.verify.RuntimeEvidence
import name.caiyao.fakegps.verify.VerificationRequestCoordinator
import name.caiyao.fakegps.verify.VerificationRequestState
import name.caiyao.fakegps.verify.VerificationEngine
import name.caiyao.fakegps.verify.VerificationReport
import name.caiyao.fakegps.hook.BaselineExtractionGuard
import name.caiyao.fakegps.ui.SingleFlightGate
import java.util.Calendar
import java.util.UUID

sealed interface PayloadStatus {
    /** Nothing has ever been published — the hook has no config to apply. */
    data object NeverPublished : PayloadStatus

    /** A payload exists but cannot be parsed; the hook keeps its last-known-good config. */
    data object Malformed : PayloadStatus

    /** The read itself failed; we cannot tell what config the hook is running. */
    data class Unreadable(val cause: String) : PayloadStatus

    data class Ok(val schemaVersion: Int, val fieldCount: Int) : PayloadStatus {
        /** The hook rejects a version it does not recognise rather than mis-reading the payload. */
        val compatible: Boolean get() = TransportSchemaContract.supports(schemaVersion)
    }
}

data class VerifyUiState(
    val loading: Boolean = true,
    val scope: ObservationScope = ObservationScope.REAL_BASELINE,
    val payload: PayloadStatus = PayloadStatus.NeverPublished,
    val mode: String = "always_on",
    val applicability: HookApplicability = HookApplicability.APPLYING,
    val fingerprint: String? = null,
    val report: VerificationReport? = null,
    val probeStatus: ProbeUiStatus = ProbeUiStatus.NotRequested,
    val notes: List<String> = emptyList(),
    val cellCount: Int = 0,
)

class VerifyViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VerifyUiState())
    // Per-ViewModel ownership: if the scope is already cancelled, this instance is already
    // unreachable from the UI, so an unobserved claim cannot block a later screen instance.
    private val refreshGate = SingleFlightGate()
    val state: StateFlow<VerifyUiState> = _state

    fun refresh() {
        if (!refreshGate.tryStart()) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                collect()
            } catch (c: CancellationException) {
                // Structured concurrency: cancellation is how the scope tears itself down when the
                // ViewModel is cleared. Swallowing it into the generic branch would both leave the
                // coroutine "completing normally" and paint a bogus error note on a screen the user
                // has already navigated away from.
                throw c
            } catch (t: Throwable) {
                // viewModelScope installs no exception handler, so an escaping throwable takes the
                // process down; and without the finally the spinner would never clear while the
                // retry button stays disabled on `loading`. Neither is acceptable on the screen
                // whose entire job is to report what went wrong.
                //
                // The previous report is DROPPED rather than kept: leaving a stale green
                // "伪装生效" on screen after the refresh that was meant to re-check it failed would
                // present an unverified claim as a current one.
                _state.value = _state.value.copy(
                    scope = ObservationScope.REAL_BASELINE,
                    report = null,
                    probeStatus = ProbeUiStatus.NotRequested,
                    notes = listOf("读取设备信息时出错，结果无法刷新：${t.javaClass.simpleName}: ${t.message}"),
                )
            } finally {
                // Both the visible loading bit and the ownership claim are released on the main
                // dispatcher without a suspension point between them. A new click therefore
                // cannot be accepted and then have its loading state cleared by this old attempt.
                withContext(Dispatchers.Main.immediate) {
                    if (_state.value.loading) _state.value = _state.value.copy(loading = false)
                    refreshGate.finish()
                }
            }
        }
    }

    private suspend fun collect() {
            val ctx = getApplication<Application>()
            val read = ConfigPrefsSync.readPublished(ctx)
            val parsed = PublishedConfig.parse(read.textOrNull)

            val payloadStatus = when {
                read is PayloadRead.ReadError -> PayloadStatus.Unreadable(read.cause)
                read is PayloadRead.Absent -> PayloadStatus.NeverPublished
                parsed == null -> PayloadStatus.Malformed
                else -> PayloadStatus.Ok(
                    parsed.schemaVersion,
                    parsed.fields.size + parsed.unavailable.size,
                )
            }

            val unavailable = parsed?.unavailable.orEmpty()
            val configuredColumns = parsed?.fields?.keys.orEmpty() + unavailable
            val observation = DeviceObserver(
                ctx,
                configuredColumns = configuredColumns,
                unavailableColumns = unavailable,
            ).observe()

            // The hook re-reads the prefs on a timer, so a config saved seconds ago may not be in
            // force yet. Without this, saving and immediately verifying shows a deterministic red.
            val pending = PublishPropagation.isPending(
                publishedAtMs = ConfigPrefsSync.readPublishedAt(ctx),
                nowMs = System.currentTimeMillis(),
            )
            val publicationFailed = ConfigPrefsSync.hasPublicationFailure(ctx)
            val applicability = if (publicationFailed) {
                HookApplicability.PUBLICATION_FAILED
            } else {
                HookApplicability.forPayload(
                    read = read,
                    parsed = parsed,
                    currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                )
            }
            val fingerprint = read.textOrNull?.let { PublishedConfig.fingerprint(it) }

            // The same readings feed different columns depending on what they can prove. Under
            // SELF_HOOKED they are observations that confirm or refute the config; under
            // REAL_BASELINE they are untouched device values, so every configured field is honestly
            // reported as "no evidence" instead of as a failure.
            val processScope = ObservationScope.current()
            val selfHooked = processScope == ObservationScope.SELF_HOOKED
            val baseline = if (selfHooked) {
                BaselineExtractionGuard.call {
                    DeviceObserver(
                        ctx,
                        configuredColumns = configuredColumns,
                        unavailableColumns = unavailable,
                    ).observe().values
                }
            } else observation.values
            val probeDecision = if (selfHooked ||
                applicability != HookApplicability.APPLYING || fingerprint == null
            ) {
                ProbeVerificationDecision.resolve(VerificationRequestState.Idle)
            } else {
                requestProbe(ctx, fingerprint)
            }
            val scope = if (selfHooked) ObservationScope.SELF_HOOKED else probeDecision.scope
            val observed = if (selfHooked) observation.values else probeDecision.observed
            // A probe failure is a lifecycle/scope failure, not evidence that every configured
            // field mismatched. Only a delivered, correlated probe (or the debug self-hook) may
            // create field verdicts. Missing values *inside* a delivered observation remain the
            // legitimate UNOBSERVABLE field state.
            val report = if (selfHooked || probeDecision.status == ProbeUiStatus.Verified) {
                VerificationEngine.buildReport(
                    configured = parsed?.fields.orEmpty(),
                    unavailable = unavailable,
                    observed = observed,
                    baseline = baseline,
                    propagationPending = selfHooked && pending,
                )
            } else null

            _state.value = VerifyUiState(
                // The finally block releases this together with the single-flight claim. Keeping
                // it true here prevents the UI becoming clickable before ownership is released.
                loading = true,
                scope = scope,
                payload = payloadStatus,
                mode = parsed?.mode ?: "always_on",
                // Derived from what was actually read back. Substituting defaults for an unparseable
                // payload (a compatible schemaVersion, fieldsPresent=true) silently yielded APPLYING
                // and made the verdict card contradict the payload card on the same screen.
                applicability = applicability,
                fingerprint = fingerprint,
                report = report,
                probeStatus = if (selfHooked) ProbeUiStatus.NotRequested else probeDecision.status,
                notes = if (publicationFailed) {
                    listOf("数据库中的档案未能发布给 Hook；下方旧 payload 不能证明当前档案已生效") +
                        observation.notes
                } else if (scope == ObservationScope.HOOK_PROBE) {
                    probeDecision.notes
                } else observation.notes,
                cellCount = if (scope == ObservationScope.HOOK_PROBE) {
                    probeDecision.cellCount
                } else observation.cellCount,
            )
    }

    private suspend fun requestProbe(
        context: Application,
        fingerprint: String,
    ): ProbeVerificationDecision {
        val request = ProbeRequest(UUID.randomUUID().toString(), fingerprint)
        var requestState: VerificationRequestState = VerificationRequestCoordinator.start(request)
        val result = try {
            HookVerificationClient.request(context, request)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            ProbeClientResult.Failed(ProbeFailure.INTERNAL_ERROR)
        }
        requestState = when (result) {
            is ProbeClientResult.Failed -> VerificationRequestCoordinator.fail(
                requestState,
                request,
                result.failure,
            )
            is ProbeClientResult.Delivered -> VerificationRequestCoordinator.accept(
                requestState,
                result.observation,
            )
        }
        when (val terminal = requestState) {
            is VerificationRequestState.Delivered -> Log.i(
                RuntimeEvidence.PROBE_TAG,
                RuntimeEvidence.probeDelivered(terminal.request, terminal.observation.values.size),
            )
            is VerificationRequestState.Failed -> Log.i(
                RuntimeEvidence.PROBE_TAG,
                RuntimeEvidence.probeFailed(terminal.request, terminal.failure),
            )
            else -> Unit
        }
        return ProbeVerificationDecision.resolve(requestState)
    }
}
