package io.homeassistant.companion.android.widgets.entity

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout.VISIBLE
import android.widget.MultiAutoCompleteTextView.CommaTokenizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.databinding.WidgetStaticConfigureBinding
import io.homeassistant.companion.android.settings.widgets.ManageWidgetsViewModel
import io.homeassistant.companion.android.util.getHexForColor
import io.homeassistant.companion.android.widgets.BaseWidgetProvider
import io.homeassistant.companion.android.widgets.common.SingleItemArrayAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import io.homeassistant.companion.android.common.R as commonR

@AndroidEntryPoint
class EntityWidgetConfigureActivity : BaseActivity() {

    companion object {
        private const val TAG: String = "StaticWidgetConfigAct"
        private const val PIN_WIDGET_CALLBACK = "io.homeassistant.companion.android.widgets.entity.EntityWidgetConfigureActivity.PIN_WIDGET_CALLBACK"
    }

    @Inject
    lateinit var integrationUseCase: IntegrationRepository

    private var entities = LinkedHashMap<String, Entity<Any>>()

    private var selectedEntity: Entity<Any>? = null
    private var appendAttributes: Boolean = false
    private var selectedAttributeIds: ArrayList<String> = ArrayList()

    private lateinit var binding: WidgetStaticConfigureBinding

    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var requestLauncherSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the result to CANCELED.  This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        binding = WidgetStaticConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addButton.setOnClickListener {
            if (requestLauncherSetup) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getSystemService<AppWidgetManager>()?.requestPinAppWidget(
                        ComponentName(this, EntityWidget::class.java),
                        null,
                        PendingIntent.getActivity(
                            this,
                            System.currentTimeMillis().toInt(),
                            Intent(this, EntityWidgetConfigureActivity::class.java).putExtra(PIN_WIDGET_CALLBACK, true).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                    )
                } else showAddWidgetError() // this shouldn't be possible
            } else {
                onAddWidget()
            }
        }

        // Find the widget id from the intent.
        val intent = intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            requestLauncherSetup = extras.getBoolean(
                ManageWidgetsViewModel.CONFIGURE_REQUEST_LAUNCHER, false
            )
        }

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !requestLauncherSetup) {
            finish()
            return
        }

        val staticWidgetDao = AppDatabase.getInstance(applicationContext).staticWidgetDao()
        val staticWidget = staticWidgetDao.get(appWidgetId)

        val backgroundTypeValues = mutableListOf(
            getString(commonR.string.widget_background_type_daynight),
            getString(commonR.string.widget_background_type_transparent)
        )
        if (DynamicColors.isDynamicColorAvailable())
            backgroundTypeValues.add(0, getString(commonR.string.widget_background_type_dynamiccolor))
        binding.backgroundType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backgroundTypeValues)

        if (staticWidget != null) {
            binding.widgetTextConfigEntityId.setText(staticWidget.entityId)
            binding.label.setText(staticWidget.label)
            binding.textSize.setText(staticWidget.textSize.toInt().toString())
            binding.stateSeparator.setText(staticWidget.stateSeparator)
            val entity = runBlocking {
                try {
                    integrationUseCase.getEntity(staticWidget.entityId)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to get entity information", e)
                    Toast.makeText(applicationContext, commonR.string.widget_entity_fetch_error, Toast.LENGTH_LONG)
                        .show()
                    null
                }
            }

            val attributeIds = staticWidget.attributeIds
            if (!attributeIds.isNullOrEmpty()) {
                binding.appendAttributeValueCheckbox.isChecked = true
                appendAttributes = true
                for (item in attributeIds.split(','))
                    selectedAttributeIds.add(item)
                binding.widgetTextConfigAttribute.setText(attributeIds.replace(",", ", "))
                binding.attributeValueLinearLayout.visibility = VISIBLE
                binding.attributeSeparator.setText(staticWidget.attributeSeparator)
            }
            if (entity != null) {
                selectedEntity = entity as Entity<Any>?
                setupAttributes()
            }

            binding.backgroundType.setSelection(
                when {
                    staticWidget.backgroundType == WidgetBackgroundType.DYNAMICCOLOR && DynamicColors.isDynamicColorAvailable() ->
                        backgroundTypeValues.indexOf(getString(commonR.string.widget_background_type_dynamiccolor))
                    staticWidget.backgroundType == WidgetBackgroundType.TRANSPARENT ->
                        backgroundTypeValues.indexOf(getString(commonR.string.widget_background_type_transparent))
                    else ->
                        backgroundTypeValues.indexOf(getString(commonR.string.widget_background_type_daynight))
                }
            )
            binding.textColor.visibility = if (staticWidget.backgroundType == WidgetBackgroundType.TRANSPARENT) View.VISIBLE else View.GONE
            binding.textColorWhite.isChecked =
                staticWidget.textColor?.let { it.toColorInt() == ContextCompat.getColor(this, android.R.color.white) } ?: true
            binding.textColorBlack.isChecked =
                staticWidget.textColor?.let { it.toColorInt() == ContextCompat.getColor(this, commonR.color.colorWidgetButtonLabelBlack) } ?: false

            binding.addButton.setText(commonR.string.update_widget)
            binding.deleteButton.visibility = VISIBLE
            binding.deleteButton.setOnClickListener(onDeleteWidget)
        } else {
            binding.backgroundType.setSelection(0)
        }
        val entityAdapter = SingleItemArrayAdapter<Entity<Any>>(this) { it?.entityId ?: "" }

        binding.widgetTextConfigEntityId.setAdapter(entityAdapter)
        binding.widgetTextConfigEntityId.onFocusChangeListener = dropDownOnFocus
        binding.widgetTextConfigEntityId.onItemClickListener = entityDropDownOnItemClick
        binding.widgetTextConfigAttribute.onFocusChangeListener = dropDownOnFocus
        binding.widgetTextConfigAttribute.onItemClickListener = attributeDropDownOnItemClick
        binding.widgetTextConfigAttribute.setOnClickListener {
            if (!binding.widgetTextConfigAttribute.isPopupShowing) binding.widgetTextConfigAttribute.showDropDown()
        }

        binding.appendAttributeValueCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.attributeValueLinearLayout.isVisible = isChecked
            appendAttributes = isChecked
        }

        binding.backgroundType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.textColor.visibility =
                    if (parent?.adapter?.getItem(position) == getString(commonR.string.widget_background_type_transparent)) View.VISIBLE
                    else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                binding.textColor.visibility = View.GONE
            }
        }

        mainScope.launch {
            try {
                // Fetch entities
                val fetchedEntities = integrationUseCase.getEntities()
                fetchedEntities?.forEach {
                    entities[it.entityId] = it
                }
                entityAdapter.addAll(entities.values)
                entityAdapter.sort()

                runOnUiThread {
                    entityAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                // If entities fail to load, it's okay to pass
                // an empty map to the dynamicFieldAdapter
                Log.e(TAG, "Failed to query entities", e)
            }
        }
    }

    private val dropDownOnFocus = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus && view is AutoCompleteTextView) {
            view.showDropDown()
        }
    }

    private val entityDropDownOnItemClick =
        AdapterView.OnItemClickListener { parent, view, position, id ->
            selectedEntity = parent.getItemAtPosition(position) as Entity<Any>?
            setupAttributes()
        }

    private val attributeDropDownOnItemClick =
        AdapterView.OnItemClickListener { parent, _, position, _ ->
            selectedAttributeIds.add(parent.getItemAtPosition(position) as String)
        }

    private fun setupAttributes() {
        val fetchedAttributes = selectedEntity?.attributes as Map<String, String>
        val attributesAdapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line)
        binding.widgetTextConfigAttribute.setAdapter(attributesAdapter)
        attributesAdapter.addAll(*fetchedAttributes.keys.toTypedArray())
        binding.widgetTextConfigAttribute.setTokenizer(CommaTokenizer())
        runOnUiThread {
            attributesAdapter.notifyDataSetChanged()
        }
    }

    private fun onAddWidget() {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            showAddWidgetError()
            return
        }
        try {
            val context = this@EntityWidgetConfigureActivity

            // Set up a broadcast intent and pass the service call data as extras
            val intent = Intent()
            intent.action = BaseWidgetProvider.RECEIVE_DATA
            intent.component = ComponentName(context, EntityWidget::class.java)

            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

            val entity: String = if (selectedEntity == null)
                binding.widgetTextConfigEntityId.text.toString()
            else
                selectedEntity!!.entityId
            intent.putExtra(
                EntityWidget.EXTRA_ENTITY_ID,
                entity
            )

            intent.putExtra(
                EntityWidget.EXTRA_LABEL,
                binding.label.text.toString()
            )

            intent.putExtra(
                EntityWidget.EXTRA_TEXT_SIZE,
                binding.textSize.text.toString()
            )

            intent.putExtra(
                EntityWidget.EXTRA_STATE_SEPARATOR,
                binding.stateSeparator.text.toString()
            )

            if (appendAttributes) {
                val attributes = if (selectedAttributeIds.isNullOrEmpty())
                    binding.widgetTextConfigAttribute.text.toString()
                else
                    selectedAttributeIds
                intent.putExtra(
                    EntityWidget.EXTRA_ATTRIBUTE_IDS,
                    attributes
                )

                intent.putExtra(
                    EntityWidget.EXTRA_ATTRIBUTE_SEPARATOR,
                    binding.attributeSeparator.text.toString()
                )
            }

            intent.putExtra(
                EntityWidget.EXTRA_BACKGROUND_TYPE,
                when (binding.backgroundType.selectedItem as String?) {
                    getString(commonR.string.widget_background_type_dynamiccolor) -> WidgetBackgroundType.DYNAMICCOLOR
                    getString(commonR.string.widget_background_type_transparent) -> WidgetBackgroundType.TRANSPARENT
                    else -> WidgetBackgroundType.DAYNIGHT
                }
            )

            intent.putExtra(
                EntityWidget.EXTRA_TEXT_COLOR,
                if (binding.backgroundType.selectedItem as String? == getString(commonR.string.widget_background_type_transparent))
                    getHexForColor(if (binding.textColorWhite.isChecked) android.R.color.white else commonR.color.colorWidgetButtonLabelBlack)
                else null
            )

            context.sendBroadcast(intent)

            // Make sure we pass back the original appWidgetId
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Issue configuring widget", e)
            showAddWidgetError()
        }
    }

    private fun showAddWidgetError() {
        Toast.makeText(applicationContext, commonR.string.widget_creation_error, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.extras != null && intent.hasExtra(PIN_WIDGET_CALLBACK)) {
            appWidgetId = intent.extras!!.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
            onAddWidget()
        }
    }

    private var onDeleteWidget = View.OnClickListener {
        val context = this@EntityWidgetConfigureActivity
        deleteConfirmation(context)
    }

    private fun deleteConfirmation(context: Context) {
        val staticWidgetDao = AppDatabase.getInstance(context).staticWidgetDao()

        val builder: android.app.AlertDialog.Builder = android.app.AlertDialog.Builder(context)

        builder.setTitle(commonR.string.confirm_delete_this_widget_title)
        builder.setMessage(commonR.string.confirm_delete_this_widget_message)

        builder.setPositiveButton(
            commonR.string.confirm_positive
        ) { dialog, _ ->
            staticWidgetDao.delete(appWidgetId)
            dialog.dismiss()
            finish()
        }

        builder.setNegativeButton(
            commonR.string.confirm_negative
        ) { dialog, _ -> // Do nothing
            dialog.dismiss()
        }

        val alert: android.app.AlertDialog? = builder.create()
        alert?.show()
    }
}
