/**
 * LocalTemplateLogic.java - evaluation - 23 Jan 2007 11:35:56 AM - antranig
 * $URL$
 * $Id$
 **************************************************************************
 * Copyright (c) 2008 Centre for Applied Research in Educational Technologies, University of Cambridge
 * Licensed under the Educational Community License version 1.0
 * 
 * A copy of the Educational Community License has been included in this 
 * distribution and is available at: http://www.opensource.org/licenses/ecl1.php
 *
 * Aaron Zeckoski (azeckoski@gmail.com) (aaronz@vt.edu) (aaron@caret.cam.ac.uk)
 */

package org.sakaiproject.evaluation.tool;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.evaluation.constant.EvalConstants;
import org.sakaiproject.evaluation.logic.EvalAuthoringService;
import org.sakaiproject.evaluation.logic.exceptions.InUseException;
import org.sakaiproject.evaluation.logic.externals.EvalExternalLogic;
import org.sakaiproject.evaluation.model.EvalItem;
import org.sakaiproject.evaluation.model.EvalScale;
import org.sakaiproject.evaluation.model.EvalTemplate;
import org.sakaiproject.evaluation.model.EvalTemplateItem;
import org.sakaiproject.evaluation.utils.TemplateItemUtils;

/**
 * Local template local abstraction to allow for default values and central point of access for all things
 * related to creating items and templates
 * 
 * @author Antranig Basman (antranig@caret.cam.ac.uk)
 * @author Aaron Zeckoski (aaronz@vt.edu)
 */
public class LocalTemplateLogic {

   private static Log log = LogFactory.getLog(LocalTemplateLogic.class);

   private EvalExternalLogic external;
   public void setExternal(EvalExternalLogic external) {
      this.external = external;
   }

   private EvalAuthoringService authoringService;
   public void setAuthoringService(EvalAuthoringService authoringService) {
      this.authoringService = authoringService;
   }



   /*
    * Real methods below
    */


   // TEMPLATES

   public EvalTemplate fetchTemplate(Long templateId) {
      return authoringService.getTemplateById(templateId);
   }

   public void saveTemplate(EvalTemplate tosave) {
      authoringService.saveTemplate(tosave, external.getCurrentUserId());
   }

   public EvalTemplate newTemplate() {
      EvalTemplate currTemplate = new EvalTemplate(new Date(), 
            external.getCurrentUserId(), EvalConstants.TEMPLATE_TYPE_STANDARD, 
            null, "private", Boolean.FALSE);
      currTemplate.setDescription(""); // Note- somehow gives DataIntegrityViolation if null
      return currTemplate;
   }


   // TEMPLATE ITEMS

   public EvalTemplateItem fetchTemplateItem(Long itemId) {
      return authoringService.getTemplateItemById(itemId);
   }

   /**
    * Get the list of all non-instructor and non-group template items for this template
    * This probably needs to handle instructor at some point (i.e. it should display the instructor added items possibly)
    * 
    * @param templateId
    * @return
    */
   public List<EvalTemplateItem> fetchTemplateItems(Long templateId) {
      if (templateId == null) {
         return new ArrayList<EvalTemplateItem>();
      } else {
         return authoringService.getTemplateItemsForTemplate(templateId, new String[] {}, null, null);
      }
   }

   public EvalTemplateItem newTemplateItem() {
      String level = EvalConstants.HIERARCHY_LEVEL_TOP;
      String nodeId = EvalConstants.HIERARCHY_NODE_ID_NONE;

      // TODO - this should respect the current level the user is at

      // TODO currently creating a fake template (newTemplate()) so the bind does not fail, this should supposedly use a defunneler
      EvalTemplateItem newTemplateItem = new EvalTemplateItem( new Date(), 
            external.getCurrentUserId(), newTemplate(), newItem(), null, 
            EvalToolConstants.ITEM_CATEGORY_VALUES[0], level, nodeId);
      newTemplateItem.setUsesNA(new Boolean(false));
      return newTemplateItem;
   }

   /**
    * Saves the templateItem (does not save the associated item)
    * @param templateItem
    */
   public void saveTemplateItem(EvalTemplateItem templateItem) {
      /* This is a temporary hack that is only good while we are only using TOP LEVEL and NODE LEVEL.
       * Basically, we're putting everything in one combo box and this is a good way to check to see if
       * it's the top node.  Otherwise the user selected a node id so it must be at the NODE LEVEL since
       * we don't support the other levels yet.
       */
      if (templateItem.getHierarchyNodeId() != null && !templateItem.getHierarchyNodeId().equals("")
            && !templateItem.getHierarchyNodeId().equals(EvalConstants.HIERARCHY_NODE_ID_NONE)) {
         templateItem.setHierarchyLevel(EvalConstants.HIERARCHY_LEVEL_NODE);
      }
      else if (templateItem.getHierarchyNodeId() != null && !templateItem.getHierarchyNodeId().equals("")
            && templateItem.getHierarchyNodeId().equals(EvalConstants.HIERARCHY_NODE_ID_NONE)) {
         templateItem.setHierarchyLevel(EvalConstants.HIERARCHY_LEVEL_TOP);
      }

      if (templateItem.getItem() == null) {
         // failure to associate an item with this templateItem
         throw new IllegalStateException("No item is associated with this templateItem (the item is null) so it cannot be saved");
      }
      authoringService.saveTemplateItem(templateItem, external.getCurrentUserId());
   }


   /**
    * Handles the removal of a templateItem, includes security check and 
    * takes care of reordering or other items in the template<br/>
    * Blocks: splits up the block and removes the parent item if a block parent is selected for removal
    * 
    * @param templateItemId a unique id of an {@link EvalTemplateItem}
    */
   public void deleteTemplateItem(Long templateItemId) {
      String currentUserId = external.getCurrentUserId();
      if (! authoringService.canControlTemplateItem(currentUserId, templateItemId)) {
         throw new SecurityException("User ("+currentUserId+") cannot control this template item ("+templateItemId+")");
      }

      EvalTemplateItem templateItem = authoringService.getTemplateItemById(templateItemId);
      // get a list of all template items in this template
      List<EvalTemplateItem> allTemplateItems = 
         authoringService.getTemplateItemsForTemplate(templateItem.getTemplate().getId(), new String[] {}, new String[] {}, new String[] {});
      // get the list of items without child items included
      List<EvalTemplateItem> noChildList = TemplateItemUtils.getNonChildItems(allTemplateItems);

      // now remove the item and correct the display order
      int orderAdjust = 0;
      int removedItemDisplayOrder = 0;
      if (TemplateItemUtils.isBlockParent(templateItem)) {
         // remove the parent item and free up the child items into individual items if the block parent is removed
         removedItemDisplayOrder = templateItem.getDisplayOrder().intValue();
         List<EvalTemplateItem> childList = TemplateItemUtils.getChildItems(allTemplateItems, templateItem.getId());
         orderAdjust = childList.size();

         // delete parent template item and item
         Long itemId = templateItem.getItem().getId();
         authoringService.deleteTemplateItem(templateItem.getId(), currentUserId);
         // if this parent is used elsewhere then this will cause exception - EVALSYS-559
         try {
            authoringService.deleteItem(itemId, currentUserId);
         } catch (InUseException e) {
            log.info("Cannot remove block parent item ("+itemId+") - item is in use elsewhere");
         }

         // modify block children template items
         for (int i = 0; i < childList.size(); i++) {
            EvalTemplateItem child = (EvalTemplateItem) childList.get(i);
            child.setBlockParent(null);
            child.setBlockId(null);
            child.setDisplayOrder(new Integer(removedItemDisplayOrder + i));
            authoringService.saveTemplateItem(child, currentUserId);
         }

      } else { // non-block cases
         removedItemDisplayOrder = templateItem.getDisplayOrder().intValue();
         authoringService.deleteTemplateItem(templateItem.getId(), currentUserId);
      }

      // shift display-order of items below removed item
      for (int i = removedItemDisplayOrder; i < noChildList.size(); i++) {
         EvalTemplateItem ti = (EvalTemplateItem) noChildList.get(i);
         int order = ti.getDisplayOrder().intValue();
         if (order > removedItemDisplayOrder) {
            ti.setDisplayOrder(new Integer(order + orderAdjust - 1));
            authoringService.saveTemplateItem(ti, currentUserId);
         }
      }
   }


   // ITEMS

   public EvalItem fetchItem(Long itemId) {
      return authoringService.getItemById(itemId);
   }

   public void saveItem(EvalItem item) {
      connectScaleToItem(item);
      authoringService.saveItem(item, external.getCurrentUserId());
   }

   /**
    * Hides an item (this will make the item inaccessible to users)
    * 
    * @param itemId the unique id of an item
    */
   public void hideItem(Long itemId) {
      EvalItem item = fetchItem(itemId);
      item.setHidden(true);
      authoringService.saveItem(item, external.getCurrentUserId());
   }

   public void deleteItem(Long id) {
      authoringService.deleteItem(id, external.getCurrentUserId());
   }

   public EvalItem newItem() {
      EvalItem newItem = new EvalItem(new Date(), external.getCurrentUserId(), "", 
            EvalConstants.SHARING_PRIVATE, "", Boolean.FALSE);
      newItem.setCategory( EvalConstants.ITEM_CATEGORY_COURSE ); // default category
      newItem.setScale(newScale()); // create a holder for a new scale which will get overwritten or cleared out if not used
      return newItem;
   }


   // SCALES

   public EvalScale fetchScale(Long scaleId) {
      EvalScale scale = authoringService.getScaleById(scaleId);
      // TODO - hopefully this if block is only needed temporarily until RSF 0.7.3
      if (scale.getIdeal() == null) {
         scale.setIdeal(EvalToolConstants.NULL);
      }
      return scale;
   }

   public void saveScale(EvalScale scale) {
      // TODO - hopefully this if block is only needed temporarily until RSF 0.7.3
      if (scale.getIdeal() != null &&
            scale.getIdeal().equals(EvalToolConstants.NULL)) {
         scale.setIdeal(null);
      }
      authoringService.saveScale(scale, external.getCurrentUserId());
   }

   /**
    * Hides a scale (this will make the scale inaccessible to users)
    * 
    * @param scaleId the unique id of a scale
    */
   public void hideScale(Long scaleId) {
      EvalScale scale = fetchScale(scaleId);
      scale.setHidden(true);
      authoringService.saveScale(scale, external.getCurrentUserId());
   }

   public void deleteScale(Long id) {
      authoringService.deleteScale(id, external.getCurrentUserId());
   }

   public EvalScale newScale() {
      EvalScale currScale = new EvalScale(new Date(), 
            external.getCurrentUserId(), null, 
            EvalConstants.SCALE_MODE_SCALE, EvalConstants.SHARING_PRIVATE, Boolean.FALSE);
      currScale.setOptions(EvalToolConstants.defaultInitialScaleValues);
      currScale.setIdeal(EvalToolConstants.NULL); // TODO - temp until RSF 0.7.3
      return currScale;
   }


   // UTILITY METHODS

   /**
    * This will connect an item to a scale and save the scale if it is new (not persistent yet),
    * the default values for the new adhoc scale will be set,
    * this will also clear out the scale if it is not used for this type of item
    * @param item an item to be saved, can be persistent or new
    */
   private void connectScaleToItem(EvalItem item) {
      // this is here to cleanup the fake scale in case it was not needed or load a real one
      if (item.getScale() != null) {
         // only process scales for the types that use them
         if ( EvalConstants.ITEM_TYPE_SCALED.equals(item.getClassification()) ||
               EvalConstants.ITEM_TYPE_BLOCK_PARENT.equals(item.getClassification()) || // http://www.caret.cam.ac.uk/jira/browse/CTL-557
               EvalConstants.ITEM_TYPE_MULTIPLEANSWER.equals(item.getClassification()) || 
               EvalConstants.ITEM_TYPE_MULTIPLECHOICE.equals(item.getClassification()) ) {
            // for multiple type items we need to save the scale
            if ( EvalConstants.ITEM_TYPE_MULTIPLEANSWER.equals(item.getClassification()) || 
               EvalConstants.ITEM_TYPE_MULTIPLECHOICE.equals(item.getClassification()) ) {
               // only make the connection for new (non-persistent) scales
               EvalScale scale = item.getScale();
               if (scale.getId() == null) {
                  // this is a new scale which must be saved so it can be saved with the item

                  // set up default values for new scales
                  if (scale.getMode() == null) {
                     scale.setMode(EvalConstants.SCALE_MODE_ADHOC);
                  }
                  if (scale.getOwner() == null) {
                     if (item.getOwner() != null) {
                        scale.setOwner(item.getOwner());
                     } else {
                        scale.setOwner(external.getCurrentUserId());
                     }
                  }
                  if (scale.getTitle() == null) {
                     scale.setTitle(EvalConstants.SCALE_ADHOC_DEFAULT_TITLE);
                  }
               }
               // new and existing scales need to be saved
               saveScale(scale);
            } else {
               // scaled/block parent item so don't save the scale
               if (item.getScale().getId() != null && 
                     item.getScale().getOwner() == null) {
                  // this is an existing scale and we need to turn the fake one into a real one so hibernate can make the connection
                  item.setScale(authoringService.getScaleById(item.getScale().getId()));
               }
            }
         } else {
            // null out the scale as it is not used for this type of item
            item.setScale(null);            
         }
      }
   }


   /**
    * TODO - this should use the defunneler -AZ (so says antranig)
    * This is here to fix up a templateItem which is not actually correctly connected via the foreign key,
    * it is not ideal but it works
    * @param tosave
    */
//   private void connectTemplateToTI(EvalTemplateItem tosave) {
//      if (tosave.getTemplate() != null) {
//         Long templateId = tosave.getTemplate().getId();
//         if (templateId != null) {
//            // this lookup is needed so hibernate can make the connection
//            tosave.setTemplate(authoringService.getTemplateById(templateId));
//         } else {
//            // the template was not set correctly so we have to die
//            throw new NullPointerException("id is not set for the template for this templateItem (" + tosave +
//            		"), all templateItems must be associated with an existing template");
//         }
//      }
//   }

}