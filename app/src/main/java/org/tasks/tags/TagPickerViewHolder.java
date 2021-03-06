package org.tasks.tags;

import static com.todoroo.andlib.utility.AndroidUtilities.atLeastJellybeanMR1;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnCheckedChanged;
import butterknife.OnClick;
import kotlin.jvm.functions.Function2;
import org.tasks.R;
import org.tasks.data.TagData;
import org.tasks.tags.CheckBoxTriStates.State;

public class TagPickerViewHolder extends RecyclerView.ViewHolder {

  private final Context context;
  private final Function2<TagData, Boolean, State> callback;

  @BindView(R.id.text)
  TextView text;

  @BindView(R.id.checkbox)
  CheckBoxTriStates checkBox;

  private TagData tagData;

  TagPickerViewHolder(
      Context context, @NonNull View view, Function2<TagData, Boolean, State> callback) {
    super(view);
    this.callback = callback;

    ButterKnife.bind(this, view);

    this.context = context;
  }

  @OnClick(R.id.tag_row)
  void onClickRow() {
    if (tagData.getId() == null) {
      callback.invoke(tagData, true);
    } else {
      checkBox.toggle();
    }
  }

  @OnCheckedChanged(R.id.checkbox)
  void onCheckedChanged() {
    State newState = callback.invoke(tagData, checkBox.isChecked());
    updateCheckbox(newState);
  }

  public void bind(
      @NonNull TagData tagData, int color, @Nullable Integer icon, State state) {
    this.tagData = tagData;
    if (tagData.getId() == null) {
      text.setText(context.getString(R.string.create_new_tag, tagData.getName()));
      icon = R.drawable.ic_outline_add_24px;
      checkBox.setVisibility(View.GONE);
    } else {
      text.setText(tagData.getName());
      if (state == State.CHECKED) {
        checkBox.setChecked(true);
      } else {
        updateCheckbox(state);
      }
      if (icon == null) {
        icon = R.drawable.ic_outline_label_24px;
      }
    }
    Drawable original = ContextCompat.getDrawable(context, icon);
    Drawable wrapped = DrawableCompat.wrap(original.mutate());
    DrawableCompat.setTint(wrapped, color);
    if (atLeastJellybeanMR1()) {
      text.setCompoundDrawablesRelativeWithIntrinsicBounds(wrapped, null, null, null);
    } else {
      text.setCompoundDrawablesWithIntrinsicBounds(wrapped, null, null, null);
    }
  }

  private void updateCheckbox(State state) {
    checkBox.setState(state, false);
    checkBox.setVisibility(View.VISIBLE);
  }
}
