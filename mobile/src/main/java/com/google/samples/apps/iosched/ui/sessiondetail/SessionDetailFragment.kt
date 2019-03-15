/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.iosched.ui.sessiondetail

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.NavUtils
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.samples.apps.iosched.R
import com.google.samples.apps.iosched.databinding.FragmentSessionDetailBinding
import com.google.samples.apps.iosched.model.Session
import com.google.samples.apps.iosched.model.SessionId
import com.google.samples.apps.iosched.model.SpeakerId
import com.google.samples.apps.iosched.shared.analytics.AnalyticsActions
import com.google.samples.apps.iosched.shared.analytics.AnalyticsHelper
import com.google.samples.apps.iosched.shared.di.MapFeatureEnabledFlag
import com.google.samples.apps.iosched.shared.domain.users.SwapRequestParameters
import com.google.samples.apps.iosched.shared.result.EventObserver
import com.google.samples.apps.iosched.shared.util.activityViewModelProvider
import com.google.samples.apps.iosched.shared.util.toEpochMilli
import com.google.samples.apps.iosched.shared.util.viewModelProvider
import com.google.samples.apps.iosched.ui.map.MapActivity
import com.google.samples.apps.iosched.ui.messages.SnackbarMessageManager
import com.google.samples.apps.iosched.ui.prefs.SnackbarPreferenceViewModel
import com.google.samples.apps.iosched.ui.reservation.RemoveReservationDialogFragment
import com.google.samples.apps.iosched.ui.reservation.RemoveReservationDialogFragment.Companion.DIALOG_REMOVE_RESERVATION
import com.google.samples.apps.iosched.ui.reservation.RemoveReservationDialogParameters
import com.google.samples.apps.iosched.ui.reservation.SwapReservationDialogFragment
import com.google.samples.apps.iosched.ui.reservation.SwapReservationDialogFragment.Companion.DIALOG_SWAP_RESERVATION
import com.google.samples.apps.iosched.ui.setUpSnackbar
import com.google.samples.apps.iosched.ui.signin.NotificationsPreferenceDialogFragment
import com.google.samples.apps.iosched.ui.signin.NotificationsPreferenceDialogFragment.Companion.DIALOG_NOTIFICATIONS_PREFERENCE
import com.google.samples.apps.iosched.ui.signin.SignInDialogFragment
import com.google.samples.apps.iosched.ui.signin.SignInDialogFragment.Companion.DIALOG_SIGN_IN
import com.google.samples.apps.iosched.ui.speaker.SpeakerActivity
import dagger.android.support.DaggerFragment
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

class SessionDetailFragment : DaggerFragment() {

    private var shareString = ""

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject lateinit var snackbarMessageManager: SnackbarMessageManager

    private lateinit var sessionDetailViewModel: SessionDetailViewModel

    @Inject lateinit var analyticsHelper: AnalyticsHelper

    @Inject
    @field:Named("tagViewPool")
    lateinit var tagRecycledViewPool: RecycledViewPool

    @Inject
    @JvmField
    @MapFeatureEnabledFlag
    var isMapEnabled: Boolean = false

    private var session: Session? = null

    private lateinit var sessionTitle: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        sessionDetailViewModel = viewModelProvider(viewModelFactory)

        val binding = FragmentSessionDetailBinding.inflate(inflater, container, false).apply {
            viewModel = sessionDetailViewModel
            lifecycleOwner = viewLifecycleOwner
        }

        binding.sessionDetailBottomAppBar.run {
            inflateMenu(R.menu.session_detail_menu)
            menu.findItem(R.id.menu_item_map)?.isVisible = isMapEnabled
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_item_share -> {
                        ShareCompat.IntentBuilder.from(activity)
                            .setType("text/plain")
                            .setText(shareString)
                            .setChooserTitle(R.string.intent_chooser_session_detail)
                            .startChooser()
                    }
                    R.id.menu_item_star -> {
                        sessionDetailViewModel.onStarClicked()
                    }
                    R.id.menu_item_map -> {
                        val roomId = session?.room?.id
                        val startTime = session?.startTime?.toEpochMilli()
                        if (roomId != null && startTime != null) {
                            startActivity(
                                MapActivity.starterIntent(requireContext(), roomId, startTime)
                            )
                        }
                    }
                    R.id.menu_item_calendar -> {
                        sessionDetailViewModel.session.value?.let(::addToCalendar)
                    }
                }
                true
            }
        }

        binding.up.setOnClickListener {
            NavUtils.navigateUpFromSameTask(requireActivity())
        }

        val detailsAdapter = SessionDetailAdapter(
            viewLifecycleOwner,
            sessionDetailViewModel,
            tagRecycledViewPool
        )
        binding.sessionDetailRecyclerView.run {
            adapter = detailsAdapter
            itemAnimator?.run {
                addDuration = 120L
                moveDuration = 120L
                changeDuration = 120L
                removeDuration = 100L
            }
            doOnLayout {
                addOnScrollListener(
                    PushUpScrollListener(
                        binding.up, it, R.id.session_detail_title, R.id.detail_image
                    )
                )
            }
        }

        sessionDetailViewModel.session.observe(this, Observer {
            detailsAdapter.speakers = it?.speakers?.toList() ?: emptyList()
        })

        sessionDetailViewModel.relatedUserSessions.observe(this, Observer {
            detailsAdapter.related = it ?: emptyList()
        })

        sessionDetailViewModel.session.observe(this, Observer {
            session = it
            shareString = if (it == null) {
                ""
            } else {
                getString(R.string.share_text_session_detail, it.title, it.sessionUrl)
            }
        })

        sessionDetailViewModel.navigateToYouTubeAction.observe(this, EventObserver { youtubeUrl ->
            openYoutubeUrl(youtubeUrl)
        })

        sessionDetailViewModel.navigateToSessionAction.observe(this, EventObserver { sessionId ->
            startActivity(SessionDetailActivity.starterIntent(requireContext(), sessionId))
        })

        val snackbarPreferenceViewModel: SnackbarPreferenceViewModel =
            activityViewModelProvider(viewModelFactory)
        setUpSnackbar(
            sessionDetailViewModel.snackBarMessage,
            binding.snackbar,
            snackbarMessageManager,
            actionClickListener = {
                snackbarPreferenceViewModel.onStopClicked()
            }
        )

        sessionDetailViewModel.errorMessage.observe(this, EventObserver { errorMsg ->
            // TODO: Change once there's a way to show errors to the user
            Toast.makeText(this.context, errorMsg, Toast.LENGTH_LONG).show()
        })

        sessionDetailViewModel.navigateToSignInDialogAction.observe(this, EventObserver {
            openSignInDialog(requireActivity())
        })
        sessionDetailViewModel.navigateToRemoveReservationDialogAction.observe(this, EventObserver {
            openRemoveReservationDialog(requireActivity(), it)
        })
        sessionDetailViewModel.navigateToSwapReservationDialogAction.observe(this, EventObserver {
            openSwapReservationDialog(requireActivity(), it)
        })

        sessionDetailViewModel.shouldShowNotificationsPrefAction.observe(this, EventObserver {
            if (it) {
                openNotificationsPreferenceDialog()
            }
        })

        sessionDetailViewModel.navigateToSpeakerDetail.observe(this, EventObserver { speakerId ->
            requireActivity().run {
                val sharedElement =
                    findSpeakerHeadshot(binding.sessionDetailRecyclerView, speakerId)
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    this, sharedElement, getString(R.string.speaker_headshot_transition)
                )
                startActivity(SpeakerActivity.starterIntent(this, speakerId), options.toBundle())
            }
        })

        sessionDetailViewModel.navigateToSessionFeedbackAction.observe(this, EventObserver {
            SessionFeedbackFragment.createInstance(it)
                .show(requireFragmentManager(), FRAGMENT_SESSION_FEEDBACK)
        })

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        Timber.d("Loading details for session $arguments")
        sessionDetailViewModel.setSessionId(requireNotNull(arguments).getString(EXTRA_SESSION_ID))
    }

    override fun onStop() {
        super.onStop()
        // Force a refresh when this screen gets added to a backstack and user comes back to it.
        sessionDetailViewModel.setSessionId(null)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // Observing the changes from Fragment because data binding doesn't work with menu items.
        val menu = requireActivity().findViewById<BottomAppBar>(
            R.id.session_detail_bottom_app_bar
        ).menu
        val starMenu = menu.findItem(R.id.menu_item_star)
        sessionDetailViewModel.shouldShowStarInBottomNav.observe(this, Observer { showStar ->
            showStar?.let {
                if (it) {
                    starMenu.setVisible(true)
                } else {
                    starMenu.setVisible(false)
                }
            }
        })
        sessionDetailViewModel.userEvent.observe(this, Observer { userEvent ->
            userEvent?.let {
                if (it.isStarred) {
                    starMenu.setIcon(R.drawable.ic_star)
                } else {
                    starMenu.setIcon(R.drawable.ic_star_border)
                }
            }
        })

        var titleUpdated = false
        sessionDetailViewModel.session.observe(this, Observer {
            if (it != null && !titleUpdated) {
                sessionTitle = it.title
                analyticsHelper.sendScreenView("Session Details: $sessionTitle", requireActivity())
                titleUpdated = true
            }
        })
    }

    private fun openYoutubeUrl(youtubeUrl: String) {
        analyticsHelper.logUiEvent(sessionTitle, AnalyticsActions.YOUTUBE_LINK)
        startActivity(Intent(Intent.ACTION_VIEW, youtubeUrl.toUri()))
    }

    private fun openSignInDialog(activity: FragmentActivity) {
        SignInDialogFragment().show(activity.supportFragmentManager, DIALOG_SIGN_IN)
    }

    private fun openNotificationsPreferenceDialog() {
        NotificationsPreferenceDialogFragment()
            .show(requireActivity().supportFragmentManager, DIALOG_NOTIFICATIONS_PREFERENCE)
    }

    private fun openRemoveReservationDialog(
        activity: FragmentActivity,
        parameters: RemoveReservationDialogParameters
    ) {
        RemoveReservationDialogFragment.newInstance(parameters)
            .show(activity.supportFragmentManager, DIALOG_REMOVE_RESERVATION)
    }

    private fun openSwapReservationDialog(
        activity: FragmentActivity,
        parameters: SwapRequestParameters
    ) {
        SwapReservationDialogFragment.newInstance(parameters)
            .show(activity.supportFragmentManager, DIALOG_SWAP_RESERVATION)
    }

    private fun findSpeakerHeadshot(speakers: ViewGroup, speakerId: SpeakerId): View {
        speakers.forEach {
            if (it.getTag(R.id.tag_speaker_id) == speakerId) {
                return it.findViewById(R.id.speaker_item_headshot)
            }
        }
        Timber.e("Could not find view for speaker id $speakerId")
        return speakers
    }

    private fun addToCalendar(session: Session) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, session.title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, session.room?.name)
            .putExtra(CalendarContract.Events.DESCRIPTION, session.getDescription(
                getString(R.string.paragraph_delimiter),
                getString(R.string.speaker_delimiter)
            ))
            .putExtra(
                CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                session.startTime.toEpochMilli()
            )
            .putExtra(
                CalendarContract.EXTRA_EVENT_END_TIME,
                session.endTime.toEpochMilli()
            )
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        }
    }

    companion object {
        private const val FRAGMENT_SESSION_FEEDBACK = "feedback"

        private const val EXTRA_SESSION_ID = "SESSION_ID"

        fun newInstance(sessionId: SessionId): SessionDetailFragment {
            val bundle = Bundle().apply { putString(EXTRA_SESSION_ID, sessionId) }
            return SessionDetailFragment().apply { arguments = bundle }
        }
    }
}
